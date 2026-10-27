package com.hs.memory.search;

import com.hs.memory.api.SearchRequest;
import com.hs.memory.api.SearchResponse;
import com.hs.memory.config.RuntimeConfig;
import com.hs.memory.embed.Tokenizer;
import com.hs.memory.repo.ItemRow;
import com.hs.memory.repo.MemoryRepository;
import com.hs.memory.rerank.RerankException;
import com.hs.memory.rerank.Reranker;
import com.hs.memory.tier.TierPolicy;
import com.hs.memory.vector.QdrantClient;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final Set<String> KINDS = Set.of("preference", "fact", "episode");
    private static final Set<String> SCOPES = Set.of("user", "project", "global", "agent");
    /**
     * 精排分低于这个值的条目直接丢弃，不占注入位。
     *
     * <p>它们能进来只是因为向量路凑满了名次（k-NN 永远有「最近的 k 个」），
     * 精排已经明确判为不相关，再注入就只是噪声。
     */
    private static final double RERANK_DROP_BELOW = 0.02;

    private final RuntimeConfig config;
    private final MemoryRepository repo;
    private final QdrantClient qdrant;
    private final com.hs.memory.asset.AssetService assets;
    private final com.hs.memory.obs.MetricsRecorder metrics;

    public SearchService(RuntimeConfig config, MemoryRepository repo, QdrantClient qdrant,
                         com.hs.memory.asset.AssetService assets,
                         com.hs.memory.obs.MetricsRecorder metrics) {
        this.config = config;
        this.repo = repo;
        this.qdrant = qdrant;
        this.assets = assets;
        this.metrics = metrics;
    }

    public SearchResponse search(String tenantId, SearchRequest req) {
        long t0 = System.nanoTime();
        String mode = "hybrid";
        try {
            // 三档策略来自控制台，热生效：off / abstain / always。
            // always 在 doSearch 内部就做第二路召回（多一次嵌入）；abstain 只在这里兜一层，
            // 代价只在真正弃权时才付。
            String strategy = config.snapshot().squeezeRetry();
            // 落选候选留给重试用：LLM 那条路要看着候选原文挑词，才可能把「备份」对上「pg_dump」。
            List<String> candidates = new ArrayList<>();
            SearchResponse response = doSearch(tenantId, req, t0, true, "always".equals(strategy),
                    candidates);
            // 弃权之后还有一次机会：换一种问法再检索一遍。
            //
            // 而且原结果已经算出来了，重试不成就丢掉，不会让原本能命中的东西变差——
            // 这正是它与 docs/query-rewrite.md 里被证伪的「替换式扩展」的区别：
            // 那是替换，这是并集。
            if (!"off".equals(strategy) && response.abstained()) {
                for (String retryQuery : retryQueries(strategy, req.query(), candidates)) {
                    SearchResponse retry = doSearch(tenantId, withQuery(req, retryQuery), t0,
                            false, false, null);
                    if (!retry.abstained() && !retry.hits().isEmpty()) {
                        log.info("弃权重试命中：{} -> {}", abbreviate(req.query()), retryQuery);
                        metrics.search(retry.route(), "hit", System.nanoTime() - t0);
                        return new SearchResponse(req.query(), retry.route(), false, retry.count(),
                                retry.hits(), retry.injection(), retry.elapsedMs(),
                                append(retry.degradeReason(),
                                        ("llm".equals(strategy) ? "查询由模型改写：" : "查询已压缩重试：")
                                                + retryQuery),
                                retry.rerank());
                    }
                }
            }
            mode = response.route();
            metrics.search(mode, outcome(response), System.nanoTime() - t0);
            return response;
        } catch (RuntimeException e) {
            // 失败也要计时：只看成功的那些，等于把最慢的请求从统计里删掉了。
            metrics.search(mode, "error", System.nanoTime() - t0);
            throw e;
        }
    }

    /**
     * 弃权后依次尝试的替代查询。按策略产出 0~2 条，命中一条就停。
     *
     * <p>两条路的取向不同，所以可以叠加：
     * <ul>
     *   <li>{@code rules}：删掉确定有害的疑问/套话，便宜、零延迟，但只认枚举过的形状；</li>
     *   <li>{@code llm}：看着落选候选改写，能跨词汇（「备份」→「pg_dump」），代价一次模型调用。</li>
     * </ul>
     * {@code both} 先试规则——它免费，能解决的情况不必付模型的钱。
     */
    private List<String> retryQueries(String strategy, String original, List<String> candidates) {
        List<String> out = new ArrayList<>(2);
        boolean rules = strategy.startsWith("rules") || "both".equals(strategy)
                || "abstain".equals(strategy);
        boolean llm = strategy.startsWith("llm") || "both".equals(strategy);
        if (rules) {
            String squeezed = QuerySqueeze.squeeze(original);
            if (QuerySqueeze.worthRetry(original, squeezed)) {
                out.add(squeezed);
            }
        }
        if (llm) {
            LlmQueryDistiller distiller = config.distiller();
            if (distiller != null) {
                long t = System.nanoTime();
                String rewritten = distiller.distill(original, candidates);
                metrics.searchStage("distill", System.nanoTime() - t);
                if (rewritten != null && !rewritten.isBlank()
                        && !rewritten.equals(original) && !out.contains(rewritten)) {
                    out.add(rewritten);
                }
            }
        }
        return out;
    }

    /** 换掉 query，其余检索条件原样保留——重试只该改查询词，不该顺带改过滤条件。 */
    private static SearchRequest withQuery(SearchRequest req, String query) {
        return new SearchRequest(query, req.topK(), req.mode(), req.projectId(), req.userId(),
                req.sessionId(), req.asOf(), req.kinds(), req.scopes(), req.tiers());
    }

    private static String abbreviate(String q) {
        if (q == null) {
            return "";
        }
        String t = q.replaceAll("\\s+", " ").trim();
        return t.length() <= 40 ? t : t.substring(0, 40) + "…";
    }

    private static String append(String a, String b) {
        return a == null || a.isBlank() ? b : a + "；" + b;
    }

    /**
     * 三种结果分开记：{@code empty}（两路都没候选）、{@code abstain}（有候选但没一条够格）、
     * {@code hit}。只看错误率会把「一直在弃权」当成健康——那才是召回最隐蔽的病。
     */
    private static String outcome(SearchResponse r) {
        if (!r.hits().isEmpty()) {
            return "hit";
        }
        if (r.injection() == null || r.injection().isBlank()) {
            return "empty";
        }
        return "abstain";
    }

    /**
     * @param softAbstain 弃权时若还有候选过了最小分，是否给一条「低置信」而不是直接兜底。
     *                    压缩重试那一趟传 {@code false}：重试本来就只是第二次机会，
     *                    不该再用灰区内容把一个「仍然没把握」的结果粉饰成命中。
     * @param multiQuery  是否把压缩查询作为<b>第二路</b>一起召回（always 模式）。
     *                    与整条链路重跑不同：只多一次嵌入 + 一次查询，精排仍用原 query。
     * @param candidateOut 非 null 时，把落选候选的原文填进去（供 LLM 改写时参考）。
     *                     弃权分支会把它们丢掉，所以要在丢弃之前取。
     */
    private SearchResponse doSearch(String tenantId, SearchRequest req, long t0,
                                    boolean softAbstain, boolean multiQuery,
                                    List<String> candidateOut) {
        // 每次取一次快照：配置可能刚在控制台改过，本次请求就应按新值走。
        RuntimeConfig.Snapshot settings = config.snapshot();
        String query = req.query() == null ? "" : req.query().trim();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        String mode = req.mode() == null ? "hybrid" : req.mode().toLowerCase(Locale.ROOT);
        if (!Set.of("hybrid", "bm25", "dense").contains(mode)) {
            throw new IllegalArgumentException("mode 只能是 hybrid / bm25 / dense");
        }
        int topK = req.topK() == null ? settings.defaultTopK() : req.topK();
        topK = Math.max(1, Math.min(topK, settings.maxTopK()));

        List<String> kinds = validate(req.kinds(), KINDS, "kinds");
        List<String> scopes = validate(req.scopes(), SCOPES, "scopes");
        List<String> tiers = validate(req.tiers(), Set.copyOf(TierPolicy.ALL), "tiers");
        if (tiers.isEmpty()) {
            tiers = TierPolicy.DEFAULT_SEARCH;
        }
        OffsetDateTime asOf = parseAsOf(req.asOf());

        List<String> tokens = Tokenizer.tokenize(query);
        String bm25Query = String.join(" ", tokens);

        // 向量只算一次。embedding 是整条链路里最贵的一步（公网 150~560ms），一次请求只付一次。
        float[] vector = null;
        String embedDegrade = null;
        if (!"bm25".equals(mode)) {
            long stage = System.nanoTime();
            try {
                vector = config.embedding().embed(query);
            } catch (Exception e) {
                embedDegrade = "向量路降级（只走 BM25）: " + e.getMessage();
                metrics.degrade("vector");
                log.warn("嵌入失败，降级为 BM25: {}", e.getMessage());
            } finally {
                metrics.searchStage("embed", System.nanoTime() - stage);
            }
        }

        // 检索<b>始终在租户内全域进行</b>，projectId 只作排序偏好（见 Ranking.projectBoost）。
        //
        // 早先是按 projectId 硬过滤，后来改成「本投影答不上来再换全租户」，两次都不对：
        // 桌面插件写死 projectId=dsh，而记录可能写在 mnemo 投影下；只要 dsh 里有几条勉强
        // 沾边的记录（比如问备份、dsh 里正好有一堆 Qdrant 的资料），这一次就算「答上来了」，
        // 永远走不到全租户那一遍，真正相关的那条被挡在外面。**「答没答上来」这个中间判断
        // 本身就是不可靠的**，不如一次就把全部候选拿出来，让相关性自己排。
        //
        // 隔离并没有变松：租户由令牌决定，跨租户一条都拿不到。
        // always 模式：把压缩查询作为第二路。只在配置允许时才算——正常回合不该为
        // 一次可能没用的召回多付 150~560ms 的嵌入。
        String squeezeText = null;
        float[] squeezeVector = null;
        if (multiQuery) {
            String squeezed = QuerySqueeze.squeeze(query);
            if (QuerySqueeze.worthRetry(query, squeezed)) {
                squeezeText = squeezed;
                try {
                    squeezeVector = config.embedding().embed(squeezed);
                } catch (Exception e) {
                    log.warn("压缩查询嵌入失败（第二路只剩 BM25）: {}", e.getMessage());
                }
            }
        }
        Pass pass = retrieve(tenantId, null, mode, bm25Query, kinds, scopes, tiers,
                asOf, settings, vector, squeezeText, squeezeVector);
        return answer(tenantId, req, settings, query, asOf, topK, pass,
                degrade(embedDegrade, pass), t0, softAbstain, candidateOut);
    }

    private static String degrade(String embedDegrade, Pass pass) {
        if (embedDegrade == null) {
            return pass.degradeReason();
        }
        return pass.degradeReason() == null ? embedDegrade : embedDegrade + "；" + pass.degradeReason();
    }

    /** 把一次检索尝试变成响应：精排、剪枝、打分、注入块。 */
    private SearchResponse answer(String tenantId, SearchRequest req,
                                  RuntimeConfig.Snapshot settings, String query, OffsetDateTime asOf,
                                  int topK, Pass pass, String degradeReason, long t0,
                                  boolean softAbstain, List<String> candidateOut) {
        List<UUID> bm25Ids = pass.bm25Ids();
        List<UUID> denseIds = pass.denseIds();
        // 原始相似度留着：它不随名次归一化改变，是判断「到底有多像」的唯一凭据。
        Map<UUID, Double> denseScores = pass.denseScores();
        List<Ranking.Fused> fused = pass.fused();
        if (fused.isEmpty()) {
            return empty(query, bm25Ids.isEmpty() ? (denseIds.isEmpty() ? "none" : "dense") : "bm25",
                    t0, degradeReason, "off");
        }

        List<UUID> ids = Ranking.unionIds(fused);
        Map<UUID, ItemRow> rows = new HashMap<>();
        for (ItemRow row : repo.findByIds(tenantId, ids, asOf)) {
            rows.put(row.id(), row);
        }

        double rrfMax = fused.get(0).rrf();
        double rrfSecond = fused.size() > 1 ? fused.get(1).rrf() : 0;
        double gap = rrfMax <= 0 ? 0 : (rrfMax - rrfSecond) / rrfMax;

        // ---- 精排：融合之后、截断之前。跳过与失败都必须能退回 RRF 顺序 ----
        Map<UUID, Double> rerankScores = new HashMap<>();
        String rerankState = "off";
        String rerankNote = null;
        Reranker reranker = config.reranker();
        if (reranker.available()) {
            // 精排窗口至少要覆盖调用方要的条数，否则 topK 调大是空转。
            //
            // 0.9.5 起「精排生效时只回精排过的条目」，于是输出条数永远 ≤ rerank.top-n。
            // 之前把 topK 从 12 调到 30、召回只从 66.1% 动到 66.6%，就是这个上限在卡着，
            // 不是召回能力到顶了。窗口上限 64：一次 cross-encoder 打太多条会被对端超时掐断，
            // 那会退回 RRF，比少排几条更糟。
            int window = Math.min(Math.max(settings.rerankTopN(), topK), MAX_RERANK_WINDOW);
            int head = Math.min(window, fused.size());
            if (head < 2) {
                // 只有一条时精排没有比较对象，跳过；两条也要排，否则「谁更相关」永远由名次决定
                rerankState = "skipped";
                rerankNote = "候选仅 " + head + " 条，精排没有区分空间";
            } else if (settings.rerankSkipGap() > 0 && gap > settings.rerankSkipGap()) {
                rerankState = "skipped";
                rerankNote = String.format(Locale.ROOT, "第一名领先 %.2f（阈值 %.2f），跳过精排",
                        gap, settings.rerankSkipGap());
            } else {
                List<UUID> headIds = new ArrayList<>();
                List<String> docs = new ArrayList<>();
                for (int i = 0; i < head; i++) {
                    ItemRow row = rows.get(fused.get(i).id());
                    if (row == null || row.narrative() == null || row.narrative().isBlank()) {
                        continue;
                    }
                    headIds.add(row.id());
                    docs.add(row.narrative());
                }
                if (docs.size() < 2) {
                    rerankState = "skipped";
                    rerankNote = "可精排的文本不足 2 条";
                } else {
                    long stage = System.nanoTime();
                    try {
                        double[] raw = reranker.score(query, docs);
                        for (int i = 0; i < headIds.size() && i < raw.length; i++) {
                            rerankScores.put(headIds.get(i), raw[i]);
                        }
                        rerankState = "applied";
                    } catch (RerankException e) {
                        rerankState = "failed";
                        rerankNote = "精排失败，退回 RRF 顺序: " + e.getMessage();
                        metrics.degrade("rerank");
                        log.warn("rerank 降级: {}", e.getMessage());
                    } finally {
                        metrics.searchStage("rerank", System.nanoTime() - stage);
                    }
                }
            }
        }
        // 精排是本链路里第二贵的调用（公网 ~120ms），它的三种状态直接影响召回质量：
        // 一直 skipped 说明阈值设宽了，一直 failed 说明对端在超时。
        metrics.rerank(rerankState);
        if (rerankNote != null) {
            degradeReason = degradeReason == null ? rerankNote : degradeReason + "；" + rerankNote;
        }

        double rrfDiv = rrfMax <= 0 ? 1 : rrfMax;
        Instant now = Instant.now();
        // 附件一次批量取回：逐条查会变成 N 次往返，而召回预算只有几百毫秒
        Map<UUID, List<com.hs.memory.asset.AssetService.AssetView>> assetsByMemory = Map.of();
        if (!ids.isEmpty()) {
            try {
                assetsByMemory = assets.byMemory(List.of(tenantId), ids);
            } catch (Exception e) {
                log.warn("附件读取失败（不影响文本召回）: {}", e.getMessage());
            }
        }
        List<SearchResponse.Hit> hits = new ArrayList<>();
        for (Ranking.Fused f : fused) {
            ItemRow row = rows.get(f.id());
            if (row == null) {
                continue; // 已删除或已退休：向量库里可能还留着，以 PG 为准
            }
            double rrfNorm = f.rrf() / rrfDiv;
            Double rel = rerankScores.get(f.id());
            // 没送精排的条目用 rrfNorm 顶替 relNorm：0.55 + 0.20 恰好回到 0.75，不缩水。
            double relNorm = rel != null ? rel : rrfNorm;
            double recency = Ranking.recency(row.createdAt(), now, row.kind());
            // 层级加成（T0 +0.06 / T1 +0.03 / T3 −0.12）之前漏传了，等于分层只影响治理、
            // 不影响排序——被钉住的 T0 和普通 T2 同分。这里补上，再把「是不是本投影」作为
            // 一个很小的偏好（0.03）加在外面：同等相关时本投影优先，但不至于压过明显更相关的
            // 跨投影记录。
            double score = Ranking.finalScore(rrfNorm, relNorm, recency, row.importance(),
                    row.citedCount(), row.correctionCount(), row.trustLevel(),
                    TierPolicy.boost(row.tier()))
                    + Ranking.projectBoost(row.projectId(), req.projectId());
            hits.add(new SearchResponse.Hit(
                    row.id().toString(), row.kind(), row.scope(), row.tier(), row.trustLevel(),
                    row.narrative(), round4(score), round6(f.rrf()), f.bm25Rank(), f.denseRank(),
                    round4(denseScores.get(f.id())), round4(rel),
                    round4(recency), row.importance(),
                    asString(row.validFrom()), asString(row.validTo()), asString(row.createdAt()),
                    assetRefs(assetsByMemory.get(f.id()))));
        }

        // 分数要在剪枝<b>之前</b>取：剪枝本身就是「这几条被判为不相关」的结论，
        // 剪完再看就只剩没进精排窗口的条目（rerankScore 全 null），拿它们的向量分当
        // 证据等于把精排的否决票作废——问天气也会注入四条旧记忆。
        double bestRerank = Double.NaN;
        double bestDense = Double.NaN;
        for (SearchResponse.Hit h : hits) {
            if (h.rerankScore() != null) {
                bestRerank = Double.isNaN(bestRerank) ? h.rerankScore() : Math.max(bestRerank, h.rerankScore());
            }
            if (h.denseScore() != null) {
                bestDense = Double.isNaN(bestDense) ? h.denseScore() : Math.max(bestDense, h.denseScore());
            }
        }
        if ("applied".equals(rerankState)) {
            // 精排窗口（rerank-top-n）外的条目没有精排分，它们的 relNorm 只能用 rrfNorm 顶替，
            // 分数被抬到跟真相关的条目同一量级——混在一起排，「分数」这一列必然忽高忽低
            // （0.765 后面跟着 0.792），而且注入块里会掺进一堆没人核过的记录。
            // 既然这一轮已经具备判断能力（精排跑成了），就只回判断过的条目：宁可少给几条，
            // 也不要为了凑满 topK 把「不知道好不好」的东西当成命中。
            hits.removeIf(h -> h.rerankScore() == null);
            hits.removeIf(h -> h.rerankScore() < RERANK_DROP_BELOW);
        }
        hits.sort(Comparator.comparingDouble(SearchResponse.Hit::score).reversed());
        if (hits.size() > topK) {
            hits = new ArrayList<>(hits.subList(0, topK));
        }
        if (candidateOut != null) {
            // 落选候选要在<b>丢弃之前</b>取：弃权分支会把 hits 清空，而模型改写恰恰需要
            // 看着这些「差一点」的原文挑词，才能把用户的口语对上库里的写法。
            for (SearchResponse.Hit h : hits) {
                if (h.narrative() != null && !h.narrative().isBlank()) {
                    candidateOut.add(h.narrative());
                    if (candidateOut.size() >= 3) {
                        break;
                    }
                }
            }
        }

        boolean abstained = Ranking.abstain(bestRerank, bestDense, "applied".equals(rerankState),
                settings.abstainThreshold());        String route = !bm25Ids.isEmpty() && !denseIds.isEmpty() ? "hybrid"
                : (!bm25Ids.isEmpty() ? "bm25" : "dense");

        if (abstained) {
            // 灰区：还有候选过了最小分，只是最佳精排分没够到放弃阈值。
            // 这一档以前直接并入弃权、退回「最近 3 条」，于是与 query 无关的同一段
            // 每轮原样出现，看着像检索没生效。但「没够阈值」不等于「无关」——线上实测
            // 「备份文件放在哪里」这种正常问法只有 0.0x，因为被检索的记录里没有「目录」，
            // 精排就把分扣穿了。给一条、并明写低置信，比换成无关的最近 3 条更有用。
            if (softAbstain && !hits.isEmpty()) {
                SearchResponse.Hit top = hits.get(0);
                List<Ranking.InjectionEntry> grey = List.of(new Ranking.InjectionEntry(
                        top.kind(), top.tier(), top.score(), top.narrative(), top.trustLevel(),
                        top.assets().stream()
                                .map(SearchResponse.AssetRef::caption)
                                .filter(java.util.Objects::nonNull)
                                .toList()));
                String low = Ranking.renderLowConfidence(grey, settings.injectionBudgetChars(), query);
                if (!low.isBlank()) {
                    return new SearchResponse(query, route, true, 0, List.of(), low, elapsed(t0),
                            degradeReason, rerankState);
                }
            }
            // 弃权本身不变（c_wrong >> c_miss），但不再「什么都不给」：0.9.3 起回退到最近
            // 几条，让模型知道记忆里存着什么。不给这一块时，问得泛一点就整块空，模型连
            // 「有记忆系统」都不知道，也就不会去调检索工具。
            String fallback = renderFallback(tenantId, req.projectId(), settings);
            return new SearchResponse(query, route, true, 0, List.of(), fallback, elapsed(t0),
                    degradeReason, rerankState);
        }

        // ---- 邻域回填：命中一条，把它同会话的前后几轮一起给模型 ----
        // 一条记忆 = 一轮对话，单独给出去常常缺前因后果（「她答应了」离开上一轮不可解）。
        // LoCoMo 上漏掉的证据约 40% 落在已召回轮次的 ±3 轮以内，补邻居比继续加深检索便宜得多。
        Map<UUID, List<ItemRow>> neighbours = neighbourRows(tenantId, rows, hits, settings, asOf);

        List<Ranking.InjectionEntry> entries = new ArrayList<>();
        List<UUID> touched = new ArrayList<>();
        for (SearchResponse.Hit h : hits) {
            entries.add(new Ranking.InjectionEntry(h.kind(), h.tier(), h.score(), h.narrative(),
                    h.trustLevel(),
                    h.assets().stream()
                            .map(SearchResponse.AssetRef::caption)
                            .filter(java.util.Objects::nonNull)
                            .toList()));
            // 邻居紧跟在它所属的命中后面：同一段话连着读，模型才串得起因果。
            for (ItemRow n : neighbours.getOrDefault(UUID.fromString(h.id()), List.of())) {
                entries.add(Ranking.InjectionEntry.neighbour(n.tier(), n.narrative(), n.trustLevel()));
            }
            touched.add(UUID.fromString(h.id()));
        }
        // query 参与裁剪：长记忆留哪一段由它和 query 的词面交集决定，不再从头上硬砍。
        String injection = Ranking.renderInjection(entries, settings.injectionBudgetChars(), query);
        try {
            repo.touchAccess(tenantId, touched);
        } catch (Exception e) {
            log.debug("access_count 更新失败（不影响召回）: {}", e.getMessage());
        }

        return new SearchResponse(query, route, false, hits.size(), hits, injection,
                elapsed(t0), degradeReason, rerankState);
    }

    /** 兜底块最多几条。3 条够提醒「记忆里有这些」，再多就挤占这一轮真正的内容。 */
    private static final int FALLBACK_ITEMS = 3;

    /**
     * 精排窗口的硬上限。窗口跟随 topK 放宽，但不能无限放宽：
     * 一次把上百条送 cross-encoder，对端多半超时，那会整体退回 RRF——比少排几条更糟。
     */
    private static final int MAX_RERANK_WINDOW = 64;

    /**
     * 最多给几条命中做邻域扩展。
     *
     * <p>只取前几条：尾部命中的「上下文价值」递减，而每次回填都是一次额外的库查询 +
     * 一段注入预算。6 条覆盖了绝大多数「一轮指代上一轮」的情形。
     */
    private static final int NEIGHBOR_ANCHORS = 6;

    /**
     * 取命中条目的同会话邻居。
     *
     * <p>约束三条：① 只对前 {@link #NEIGHBOR_ANCHORS} 条命中扩展；② 邻居不能是已命中的条目
     * （否则注入块里同一条出现两次）；③ 查询失败一律吞掉——回填是锦上添花，不能因为它让检索报错。
     *
     * @return 命中 id → 它要带的邻居（按轮次排好）
     */
    private Map<UUID, List<ItemRow>> neighbourRows(String tenantId, Map<UUID, ItemRow> rows,
                                                   List<SearchResponse.Hit> hits,
                                                   RuntimeConfig.Snapshot settings, OffsetDateTime asOf) {
        int radius = settings.neighborRadius();
        int max = settings.neighborMax();
        if (radius <= 0 || max <= 0 || hits.isEmpty()) {
            return Map.of();
        }
        Set<UUID> exclude = new HashSet<>();
        List<MemoryRepository.TurnRef> anchors = new ArrayList<>();
        Map<String, List<UUID>> bySession = new HashMap<>();
        for (SearchResponse.Hit h : hits) {
            UUID id = UUID.fromString(h.id());
            exclude.add(id);
            if (anchors.size() >= NEIGHBOR_ANCHORS) {
                continue;
            }
            ItemRow row = rows.get(id);
            if (row == null || !row.hasTurn()) {
                continue; // 没有会话/轮次的记录（手工写入、跨会话摘要）本来就没有邻居
            }
            anchors.add(new MemoryRepository.TurnRef(row.sessionId(), row.turn()));
            bySession.computeIfAbsent(row.sessionId(), k -> new ArrayList<>()).add(id);
        }
        if (anchors.isEmpty()) {
            return Map.of();
        }
        List<ItemRow> found;
        try {
            found = repo.neighbors(tenantId, anchors, radius, exclude, asOf, max);
        } catch (Exception e) {
            log.warn("邻域回填失败（不影响本轮召回）: {}", e.getMessage());
            return Map.of();
        }
        Map<UUID, List<ItemRow>> out = new HashMap<>();
        for (ItemRow n : found) {
            UUID parent = nearestAnchor(n, bySession.get(n.sessionId()), rows);
            if (parent != null) {
                out.computeIfAbsent(parent, k -> new ArrayList<>()).add(n);
            }
        }
        return out;
    }

    /** 一条邻居归给同会话里轮次最近的那条命中：注入块里它要紧跟着最相关的那一句。 */
    private static UUID nearestAnchor(ItemRow n, List<UUID> candidates, Map<UUID, ItemRow> rows) {
        if (candidates == null || candidates.isEmpty() || n.turn() == null) {
            return null;
        }
        UUID best = null;
        int bestGap = Integer.MAX_VALUE;
        for (UUID c : candidates) {
            ItemRow p = rows.get(c);
            if (p == null || !p.hasTurn() || !p.sessionId().equals(n.sessionId())) {
                continue;
            }
            int gap = Math.abs(p.turn() - n.turn());
            // 同距时按 id 定序，保证同一份数据每次跑出来的注入块完全一样（便于对比实验）。
            if (gap < bestGap || (gap == bestGap && (best == null || c.toString().compareTo(best.toString()) < 0))) {
                bestGap = gap;
                best = c;
            }
        }
        return best;
    }

    /**
     * 无命中时的兜底：最近几条 active 记录，单独标题，明写「未命中直接相关」。
     *
     * <p>失败一律返回空串：兜底本身是锦上添花，不能因为多查一次库就让检索报错。
     */
    private String renderFallback(String tenantId, String projectId, RuntimeConfig.Snapshot settings) {
        try {
            List<Ranking.InjectionEntry> entries = new ArrayList<>();
            List<MemoryRepository.PinnedRow> recent = repo.listRecent(tenantId, projectId, FALLBACK_ITEMS);
            // 投影里没有近期记录（新投影、或记录都写在别的投影下）：兜底也别空着，
            // 退回整个租户。兜底块本来就只是「最近的记忆」，不是证据，跨投影没有误导性。
            if (recent.isEmpty() && projectId != null && !projectId.isBlank()) {
                recent = repo.listRecent(tenantId, null, FALLBACK_ITEMS);
            }
            for (MemoryRepository.PinnedRow row : recent) {
                entries.add(new Ranking.InjectionEntry(row.kind(), row.tier(), row.importance(),
                        row.narrative(), row.trustLevel(), List.of()));
            }
            return Ranking.renderRecent(entries, settings.injectionBudgetChars());
        } catch (Exception e) {
            log.debug("兜底注入失败（不影响检索）: {}", e.getMessage());
            return "";
        }
    }

    private SearchResponse empty(String query, String route, long t0, String degrade, String rerank) {
        return new SearchResponse(query, route, true, 0, List.of(), "", elapsed(t0), degrade, rerank);
    }

    /** 一次检索尝试的结果：两路候选、融合结果与降级原因。 */
    private record Pass(List<UUID> bm25Ids, List<UUID> denseIds, Map<UUID, Double> denseScores,
                        List<Ranking.Fused> fused, String degradeReason) {
    }

    /**
     * 在一个投影上跑 BM25 + 向量两路。{@code projectId} 为 null 表示不限投影。
     *
     * <p>{@code vector} 由调用方算好并复用；为 null 表示向量路不可用（嵌入失败或
     * mode=bm25），此时只走 BM25，不报错。
     */
    /**
     * 在一个投影上跑 BM25 + 向量两路。{@code projectId} 为 null 表示不限投影。
     *
     * <p>后两个参数是「压缩查询」的第二路：非空时同一对 bm25 / 向量再查一遍，结果按 id
     * 并集、向量分取较高值。这是 always 模式的实现——它<b>不替换</b>查询，而是多问一次，
     * 所以原本能命中的东西不会消失（与 {@code docs/query-rewrite.md} 里被证伪的
     * 「替换式扩展」正相反）。精排仍然只用原 query，否则两路的分数不可比。
     */
    private Pass retrieve(String tenantId, String projectId, String mode, String bm25Query,
                          List<String> kinds, List<String> scopes, List<String> tiers,
                          OffsetDateTime asOf, RuntimeConfig.Snapshot settings, float[] vector,
                          String squeezeText, float[] squeezeVector) {
        Set<UUID> bm25Seen = new LinkedHashSet<>();
        if (!"dense".equals(mode) && !bm25Query.isEmpty()) {
            long stage = System.nanoTime();
            try {
                collectBm25(tenantId, projectId, bm25Query, kinds, scopes, tiers, asOf,
                        settings.bm25TopK(), bm25Seen);
                if (squeezeText != null) {
                    String second = String.join(" ", Tokenizer.tokenize(squeezeText));
                    if (!second.isBlank() && !second.equals(bm25Query)) {
                        collectBm25(tenantId, projectId, second, kinds, scopes, tiers, asOf,
                                settings.bm25TopK(), bm25Seen);
                    }
                }
            } catch (Exception e) {
                // BM25 挂了不该让整次召回失败：继续走向量
                log.warn("bm25 查询失败: {}", e.getMessage());
            } finally {
                metrics.searchStage("bm25", System.nanoTime() - stage);
            }
        }
        List<UUID> bm25Ids = new ArrayList<>(bm25Seen);

        // 原始相似度留着：它不随名次归一化改变，是判断「到底有多像」的唯一凭据。
        // 用一个有序 Map 同时保存顺序与分数——两路合并后 id 去重，分数取较高的那次。
        Map<UUID, Double> denseScores = new LinkedHashMap<>();
        String degradeReason = null;
        if (vector != null && !"bm25".equals(mode)) {
            long stage = System.nanoTime();
            try {
                Map<String, Object> filter = QdrantClient.filter(tenantId, projectId, "active", tiers);
                collectDense(vector, settings, filter, denseScores);
                if (squeezeVector != null) {
                    collectDense(squeezeVector, settings, filter, denseScores);
                }
            } catch (Exception e) {
                degradeReason = "向量路降级（只走 BM25）: " + e.getMessage();
                metrics.degrade("vector");
                log.warn("dense 召回失败，降级为 BM25: {}", e.getMessage());
            } finally {
                metrics.searchStage("dense", System.nanoTime() - stage);
            }
        }
        List<UUID> denseIds = new ArrayList<>(denseScores.keySet());
        return new Pass(bm25Ids, denseIds, denseScores,
                Ranking.fuse(bm25Ids, denseIds, settings.rrfK()), degradeReason);
    }

    private void collectBm25(String tenantId, String projectId, String query,
                             List<String> kinds, List<String> scopes, List<String> tiers,
                             OffsetDateTime asOf, int limit, Set<UUID> out) {
        for (MemoryRepository.Scored s : repo.bm25(tenantId, projectId, query,
                kinds, scopes, tiers, asOf, limit)) {
            out.add(s.id());
        }
    }

    private void collectDense(float[] vector, RuntimeConfig.Snapshot settings,
                              Map<String, Object> filter, Map<UUID, Double> out) {
        for (QdrantClient.PointHit hit : qdrant.query(vector, settings.denseTopK(),
                filter, settings.denseMinScore())) {
            out.merge(hit.id(), hit.score(), Math::max);
        }
    }

    private static long elapsed(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static List<SearchResponse.AssetRef> assetRefs(
            List<com.hs.memory.asset.AssetService.AssetView> views) {
        if (views == null || views.isEmpty()) {
            return List.of();
        }
        List<SearchResponse.AssetRef> out = new ArrayList<>();
        for (com.hs.memory.asset.AssetService.AssetView v : views) {
            out.add(new SearchResponse.AssetRef(v.id(), v.kind(), v.mimeType(), v.caption(), v.url()));
        }
        return out;
    }

    private static Double round4(Double v) {
        return v == null ? null : round4(v.doubleValue());
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    private static String asString(Instant i) {
        return i == null ? null : i.toString();
    }

    /** 层级用大写存储，所以不能复用下面那个会转小写的 validate。 */
    private static List<String> validateTiers(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String v : values) {
            String t = v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
            if (!TierPolicy.ALL.contains(t)) {
                throw new IllegalArgumentException("tiers 含非法值: " + v);
            }
            out.add(t);
        }
        return out;
    }

    private static List<String> validate(List<String> values, Set<String> allowed, String name) {        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String v : values) {
            String t = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
            if (!allowed.contains(t)) {
                throw new IllegalArgumentException(name + " 含非法值: " + v);
            }
            out.add(t);
        }
        return out;
    }

    private static OffsetDateTime parseAsOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
        try {
            return Instant.parse(raw).atOffset(ZoneOffset.UTC);
        } catch (Exception ignored) {
            try {
                return LocalDate.parse(raw).atStartOfDay().atOffset(ZoneOffset.UTC);
            } catch (Exception e) {
                throw new IllegalArgumentException("asOf 需为 ISO-8601 时间或日期: " + raw);
            }
        }
    }
}
