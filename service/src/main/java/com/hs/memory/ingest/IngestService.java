package com.hs.memory.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.memory.config.RuntimeConfig;
import com.hs.memory.api.IngestRequest;
import com.hs.memory.api.IngestResponse;
import com.hs.memory.embed.Tokenizer;
import com.hs.memory.extract.ExtractException;
import com.hs.memory.extract.ExtractResult;
import com.hs.memory.extract.ExtractedItem;
import com.hs.memory.extract.Extractor;
import com.hs.memory.guard.TrustPolicy;
import com.hs.memory.guard.WriteReview;
import com.hs.memory.repo.MemoryRepository;
import com.hs.memory.repo.NewItem;
import com.hs.memory.tier.TierPolicy;
import com.hs.memory.vector.QdrantClient;
import com.hs.memory.vector.VecHash;
import com.hs.memory.vector.VectorRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final Set<String> KINDS = Set.of("preference", "fact", "episode");
    private static final Set<String> SCOPES = Set.of("user", "project", "global", "agent");
    private static final Set<String> TIERS = Set.of("T0", "T1", "T2", "T3");
    private static final Set<String> TRUST = Set.of("high", "medium", "low");
    private static final Set<String> SOURCE = Set.of("turn", "tool", "manual", "import");

    /**
     * 泛谓词：模型常给「使用 / 是 / 有」，它们不构成可区分的冲突维度。
     * 按它 retire 会把「mnemo 使用 PostgreSQL」和「mnemo 使用 BM25」判成同一条，
     * 静默吃掉一条记忆——这比一开始就没写进去更难发现。
     */
    private static final Set<String> GENERIC_PREDICATES = Set.of(
            "使用", "是", "有", "为", "用", "属于", "包括", "包含", "采用", "拥有", "具",
            "use", "uses", "is", "has", "has_a");
    /** 段落目标长度。设计稿的上限是 600，正好是注入预算里一条记忆能露出的十几倍。 */
    private static final int SEGMENT_CHARS = 600;
    /** 短于此的段落不进索引：没有可召回内容，却会永久占着词表。 */
    private static final int MIN_SEGMENT_CHARS = 8;
    /** 一轮最多切几条。防的是一段粘贴进来的长文档把一轮变成几百条记录。 */
    private static final int TURN_MAX_SEGMENTS = 12;

    private final RuntimeConfig config;
    private final MemoryRepository repo;
    private final QdrantClient qdrant;
    private final VectorRegistry vectors;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TransactionTemplate tx;
    private final com.hs.memory.obs.MetricsRecorder metrics;

    public IngestService(RuntimeConfig config, MemoryRepository repo, QdrantClient qdrant,
                         VectorRegistry vectors, PlatformTransactionManager txManager,
                         com.hs.memory.obs.MetricsRecorder metrics) {
        this.config = config;
        this.repo = repo;
        this.qdrant = qdrant;
        this.vectors = vectors;
        this.tx = new TransactionTemplate(txManager);
        this.metrics = metrics;
    }

    /**
     * @param tokenId 写下这批记录的令牌，仅用于事后追溯；没有令牌来源时传 null。
     */
    public IngestResponse ingest(String tenantId, UUID tokenId, IngestRequest req) {
        long t0 = System.nanoTime();
        try {
            IngestResponse response = doIngest(tenantId, tokenId, req);
            metrics.ingest(response.extraction(), response.accepted() > 0 ? "ok" : "empty",
                    System.nanoTime() - t0);
            return response;
        } catch (RuntimeException e) {
            metrics.ingest("unknown", "error", System.nanoTime() - t0);
            throw e;
        }
    }

    private IngestResponse doIngest(String tenantId, UUID tokenId, IngestRequest req) {
        List<IngestRequest.Item> items = req.items();
        String extraction;
        if (items != null && !items.isEmpty()) {
            extraction = "structured";
        } else if (req.turn() != null && req.turn().messages() != null && !req.turn().messages().isEmpty()) {
            TurnExtraction extracted = extractTurn(req.turn());
            items = extracted.items();
            extraction = extracted.mode();
        } else {
            throw new IllegalArgumentException("items 与 turn 至少要提供一个");
        }
        if (items == null || items.isEmpty()) {
            // 抽取模型判定「这轮没什么值得记的」是有效结论，不是失败。
            return new IngestResponse(extraction, 0, 0, List.of());
        }

        List<IngestResponse.Result> results = new ArrayList<>();
        int accepted = 0;
        int duplicates = 0;
        for (int i = 0; i < items.size(); i++) {
            Prepared prepared = prepare(tenantId, tokenId, req, items.get(i), i, items.size());
            NewItem item = prepared.item();
            Persisted persisted = persist(item);
            // 被取代的旧事实要把向量 payload 也改成 superseded：否则 dense 一路还会把它当成
            // active 送上来。PG 侧已经挡了一层（findByIds 只取 active），这里是第二层。
            if (persisted.superseded() != null) {
                try {
                    qdrant.setPayload(List.of(persisted.superseded()), Map.of("status", "superseded"));
                } catch (Exception e) {
                    log.warn("旧事实的向量 payload 未同步: {}", e.getMessage());
                }
            }
            if (persisted.duplicate()) {
                duplicates++;
                results.add(new IngestResponse.Result(persisted.id().toString(),
                        item.kind(), true, false, join(prepared.note(), "idempotency key 已存在，未重复写入")));
                continue;
            }
            accepted++;
            String note = join(prepared.note(), embedAndUpsert(item, persisted.id()));
            results.add(new IngestResponse.Result(persisted.id().toString(),
                    item.kind(), false, note == null, note));
        }
        return new IngestResponse(extraction, accepted, duplicates, results);
    }

    /** 待写条目 + 写入审查留下的说明（没有任何拦改时为 null）。 */
    private record Prepared(NewItem item, String note) {
    }

    /** {@code superseded} 是被这条顶掉的旧事实，供调用方同步向量 payload。 */
    private record Persisted(UUID id, boolean duplicate, UUID superseded) {
    }

    /** 抽取结果 + 本轮走了哪条路（回给调用方，便于判断记忆为什么没进来）。 */
    private record TurnExtraction(List<IngestRequest.Item> items, String mode) {
    }

    /**
     * 把一轮对话变成待写条目：配了抽取模型就交给模型，失败才回落按段硬切。
     *
     * <p>模型返回空列表是**有效结论**——这轮确实没什么值得记的。不能把它当失败去硬切，
     * 否则闲聊会被写成 episode，以后每次同类提问都命中一段废话，白占注入预算。
     */
    private TurnExtraction extractTurn(IngestRequest.Turn turn) {
        Extractor extractor = config.extractor();
        if (!extractor.available()) {
            return new TurnExtraction(fromTurn(turn), "episode-fallback");
        }
        try {
            long stage = System.nanoTime();
            ExtractResult result;
            try {
                result = extractor.extract(joinTurn(turn));
            } finally {
                metrics.ingestStage("extract", System.nanoTime() - stage);
            }
            log.info("抽取完成 model={} items={} latency={}ms",
                    result.model(), result.count(), result.latencyMs());
            return new TurnExtraction(toItems(result), "llm-extract");
        } catch (ExtractException e) {
            // 抽取降级成硬切：写入还能成功，但记下来的是一段原文而不是事实，值得单独计数
            metrics.degrade("extract");
            log.warn("抽取失败，回落按段硬切: {}", e.getMessage());
            return new TurnExtraction(fromTurn(turn), "episode-fallback");
        }
    }

    private String joinTurn(IngestRequest.Turn turn) {
        StringBuilder sb = new StringBuilder();
        for (IngestRequest.Message m : turn.messages()) {
            String content = m.content() == null ? "" : m.content().trim();
            if (content.isEmpty()) {
                continue;
            }
            sb.append(nullSafe(m.role())).append(": ").append(content).append('\n');
        }
        return sb.toString();
    }

    private List<IngestRequest.Item> toItems(ExtractResult result) {
        List<IngestRequest.Item> out = new ArrayList<>();
        for (ExtractedItem e : result.items()) {
            Map<String, Object> facts = new LinkedHashMap<>();
            if (e.facts() != null) {
                e.facts().forEach(facts::put);
            }
            out.add(new IngestRequest.Item(
                    e.kind(), null, null, e.narrative(), facts, e.recallHints(),
                    e.subjectKey(), e.predicate(), e.objectValue(),
                    null, e.confidence(), null, null, null, "turn", null));
        }
        return out;
    }

    /** 先查 idem，再退休旧事实，最后插入。顺序错了会把旧事实白白退休掉。 */
    private Persisted persist(NewItem item) {
        return tx.execute(status -> {
            Optional<UUID> existing = repo.findIdByKey(item.tenantId(), item.idemKey());
            if (existing.isPresent()) {
                return new Persisted(existing.get(), true, null);
            }
            UUID supersedes = null;
            if ("fact".equals(item.kind()) && item.subjectKey() != null && item.predicate() != null
                    && !isGenericPredicate(item.predicate())) {
                supersedes = repo.retireActiveFact(item.tenantId(), item.projectId(),
                        item.subjectKey(), item.predicate()).orElse(null);
            }
            // 替换旧事实时 valid_from 传 null，让 SQL 用 now()——retire 用的也是 now()，
            // 同一个事务时间戳，两条区间严丝合缝，不会因为 Java 侧早几毫秒而判定重叠。
            OffsetDateTime validFrom = supersedes == null ? item.validFrom() : null;
            NewItem withSupersedes = new NewItem(item.id(), item.tenantId(), item.tokenId(), item.userId(),
                    item.projectId(),
                    item.scope(), item.kind(), item.tier(), item.trustLevel(), item.subjectKey(),
                    item.predicate(), item.objectValue(), item.factsJson(), item.narrative(),
                    item.recallHints(), item.retrievalText(), item.searchText(),
                    item.sourceKind(), item.sessionId(), item.turn(),
                    item.confidence(), item.importance(), validFrom, item.contentHash(),
                    item.idemKey(), supersedes);
            Optional<UUID> inserted = repo.insert(withSupersedes);
            // lambda 里只能引用 effectively final，supersedes 在上面的分支里被赋过值
            final UUID retired = supersedes;
            return inserted.map(id -> new Persisted(id, false, retired))
                    .orElseGet(() -> new Persisted(repo.findIdByKey(item.tenantId(), item.idemKey())
                            .orElseThrow(() -> new IllegalStateException("插入后既无新 id 也无既有 id")), true,
                            retired));
        });
    }

    /** @return null 表示向量写入成功；否则是给调用方看的降级说明 */
    private String embedAndUpsert(NewItem item, UUID id) {
        try {
            // 向量化 narrative + recall hints：hints 是「用户将来会怎么问」的说法，
            // 只向量化叙述会让「问法」与「写法」之间的鸿沟全压在查询侧承担。
            String text = item.retrievalText() == null || item.retrievalText().isBlank()
                    ? item.narrative() : item.retrievalText();
            long stage = System.nanoTime();
            float[] vector;
            try {
                vector = config.embedding().embed(text);
            } finally {
                // 嵌入失败也算进这一段：它是最常在超时的那一步，失败样本不能丢
                metrics.ingestStage("embed", System.nanoTime() - stage);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tenant_id", item.tenantId());
            payload.put("status", "active");
            payload.put("kind", item.kind());
            payload.put("tier", item.tier());
            if (item.projectId() != null) {
                payload.put("project_id", item.projectId());
            }
            if (item.sessionId() != null) {
                payload.put("session_id", item.sessionId());
            }
            payload.put("valid_from", item.validFrom() == null ? Instant.now().getEpochSecond()
                    : item.validFrom().toEpochSecond());
            // 登记 + 摘要：后端里那份向量与这次算出来的是不是同一份，之后要能回答。
            String spaceId = config.snapshot().spaceId();
            String vecHash = VecHash.of(spaceId, vector);
            payload.put("vec_hash", vecHash);
            vectors.pending(item.tenantId(), id, spaceId, vector.length, vecHash);
            qdrant.upsert(id, vector, payload);
            vectors.ack(item.tenantId(), id, spaceId, vector.length, vecHash);
            return null;
        } catch (Exception e) {
            // 记忆已经进 PG，向量可以之后重建（reindex），所以这里不抛
            log.warn("向量写入失败 id={}: {}", id, e.getMessage());
            return "已入库但向量写入失败，仅 BM25 可召回: " + e.getMessage();
        }
    }

    private Prepared prepare(String tenantId, UUID tokenId, IngestRequest req,
                             IngestRequest.Item item, int index, int total) {
        String kind = lower(item.kind(), "episode");
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("kind 只能是 preference / fact / episode");
        }
        String scope = lower(item.scope(), "preference".equals(kind) ? "user" : "project");
        if (!SCOPES.contains(scope)) {
            throw new IllegalArgumentException("scope 只能是 user / project / global / agent");
        }
        String source = lower(item.sourceKind(), req.turn() == null ? "manual" : "turn");
        if (!SOURCE.contains(source)) {
            throw new IllegalArgumentException("sourceKind 只能是 turn / tool / manual / import");
        }
        // 用户说「记住」＝他本人授权，不是模型推断。按手工写入对待，
        // 否则最该留下的那条记忆反而会被 trust 系数和 low 拦截规则压下去。
        boolean explicit = req.turn() != null && "explicit".equalsIgnoreCase(req.turn().trigger());
        if (explicit) {
            source = "manual";
        }
        String declaredTrust = lower(item.trustLevel(), null);
        if (declaredTrust != null && !TRUST.contains(declaredTrust)) {
            throw new IllegalArgumentException("trustLevel 只能是 high / medium / low");
        }

        String subject = blankToNull(item.subjectKey());
        String predicate = blankToNull(item.predicate());
        String objectValue = blankToNull(item.objectValue());

        String narrative = blankToNull(item.narrative());
        if (narrative == null) {
            if (subject != null && predicate != null && objectValue != null) {
                narrative = subject + " " + predicate + " " + objectValue;
            } else {
                throw new IllegalArgumentException("narrative 不能为空（fact 至少给 subject/predicate/object）");
            }
        }
        if (narrative.length() > 8000) {
            narrative = narrative.substring(0, 8000);
        }

        // ---- 投毒防线 1：来源分级。声明的可信度只能往下降，不能自己往上抬 ----
        String trust = TrustPolicy.resolve(source, declaredTrust);

        // ---- 投毒防线 2：写入审查。命中命令式语句就降级为 episode 并把可信度压到 low ----
        String guardNote = null;
        if (WriteReview.downgradable(kind)) {
            WriteReview.Verdict verdict = WriteReview.review(narrative);
            metrics.review(verdict.hit() ? "downgrade" : "pass");
            if (verdict.hit()) {
                guardNote = "写入审查命中「" + verdict.label() + "」（" + verdict.matched()
                        + "），已从 " + kind + " 降级为 episode 并标记 low";
                kind = "episode";
                trust = TrustPolicy.LOW;
                scope = "preference".equals(scope) ? "user" : scope;
                log.warn("记忆降级 tenant={} label={} matched={}", tenantId, verdict.label(), verdict.matched());
            }
        }

        // 层在审查之后定：kind 被降级时，初始层也要按新 kind 重算。
        String tier = upper(item.tier(), null);
        if (tier == null) {
            tier = TierPolicy.initialTier(kind, source,
                    blankToNull(req.sessionId()) != null
                            || (req.turn() != null && blankToNull(req.turn().sessionId()) != null));
        }
        if (!TIERS.contains(tier)) {
            throw new IllegalArgumentException("tier 只能是 T0 / T1 / T2 / T3");
        }
        if (!TrustPolicy.allowsTier(trust, tier)) {
            guardNote = join(guardNote, "low 可信内容不能进 " + tier + "，已落到 T2");
            tier = TierPolicy.T2;
        }

        Map<String, Object> facts = item.facts() == null ? Map.of() : item.facts();
        String factsJson = json(facts);

        String projectId = blankToNull(item.projectId()) != null ? item.projectId().trim() : blankToNull(req.projectId());
        String userId = blankToNull(req.userId()) == null ? "" : req.userId().trim();

        List<String> hints = item.recallHints() == null ? List.of()
                : item.recallHints().stream()
                        .filter(h -> h != null && !h.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
        String hintsText = String.join(" ", hints);

        String extra = String.join(" ",
                nullSafe(subject), nullSafe(predicate), nullSafe(objectValue),
                facts.values().toString(), hintsText);
        String searchText = Tokenizer.searchText(narrative, extra);
        String retrievalText = hintsText.isEmpty() ? narrative : narrative + " " + hintsText;
        String recallHints = joinHints(hints);
        String contentHash = sha256(kind + "|" + nullSafe(subject) + "|" + nullSafe(predicate)
                + "|" + nullSafe(objectValue) + "|" + narrative);

        String baseKey = blankToNull(req.idempotencyKey());
        String idemKey;
        if (baseKey != null) {
            idemKey = total == 1 ? baseKey : baseKey + "#" + index;
        } else {
            idemKey = contentHash;
        }

        OffsetDateTime validFrom = parseTime(item.validFrom());
        double importance = clamp(item.importance() == null ? 0.5 : item.importance());
        double confidence = clamp(item.confidence() == null ? 0.7 : item.confidence());
        if (explicit) {
            confidence = Math.max(confidence, 0.9);
        }

        // 会话与轮次可能只出现在 turn 里（插件两处都发，脚本或工具只发一处）。
        // 漏掉它们，记录就失去了归属：事后既分不清哪条出自哪一轮，也没法按会话删。
        String sessionId = blankToNull(req.sessionId());
        if (sessionId == null && req.turn() != null) {
            sessionId = blankToNull(req.turn().sessionId());
        }
        Integer turnIndex = item.turn() != null ? item.turn()
                : (req.turn() == null ? null : req.turn().index());

        return new Prepared(new NewItem(UUID.randomUUID(), tenantId, tokenId, userId, projectId, scope, kind, tier,
                trust, subject, predicate, objectValue, factsJson, narrative,
                recallHints, retrievalText, searchText, source,
                sessionId, turnIndex, confidence, importance, validFrom,
                contentHash, idemKey, null), guardNote);
    }

  /**
   * 没有结构化抽取时的兜底：把一轮对话切成段落级 episode。
   *
   * <p>切开而不是整轮落一条，是因为检索单位是段落：一个 3000 字的整轮在注入时只能露出
   * 开头 240 字，而开头往往是提问，真正的答案在后面被截掉——等于花了预算返回一段问句。
   * 切段之后每一段都能独立命中、独立注入。
   *
   * <p>用户侧的纯问句直接丢弃：问句没有断言任何东西，存下来只会让以后每一次同形提问
   * 都命中一段自问自答，白占预算。用户的陈述（"我们决定用 PG18"）照常保留。
   */
  private List<IngestRequest.Item> fromTurn(IngestRequest.Turn turn) {
    List<IngestRequest.Item> out = new ArrayList<>();
    for (IngestRequest.Message m : turn.messages()) {
        String content = m.content() == null ? "" : m.content().trim();
        if (content.isEmpty()) {
            continue;
        }
        if ("user".equalsIgnoreCase(nullSafe(m.role())) && isQuestion(content)) {
            continue;
        }
        for (String segment : splitSegments(content)) {
            // 太短的段落（"好的" "收到"）没有可召回的内容，但会长期占着索引。
            if (segment.length() < MIN_SEGMENT_CHARS) {
                continue;
            }
            if (out.size() >= TURN_MAX_SEGMENTS) {
                return out;
            }
            out.add(new IngestRequest.Item("episode", "project", null, segment, Map.of(),
                    null, null, null, null, 0.5, 0.7, "medium", null, null, "turn", null));
        }
    }
    if (out.isEmpty()) {
        throw new IllegalArgumentException("turn.messages 里没有可用的 content");
    }
    return out;
  }

  /** 问句判据：以问号结尾。刻意只认问号，不做语义判断——规则越宽，误杀陈述的概率越高。 */
  static boolean isQuestion(String text) {
    String t = text.trim();
    return t.endsWith("？") || t.endsWith("?");
  }

  /**
   * 按空行分段：空行是作者写的语义边界，单换行不是。
   *
   * <p>只认空行、不认单换行，是因为中文回复里单换行多为排版（列表项、短句分行），
   * 按它切会把一句完整的话拆成两条半句记忆；而空行是作者显式写下的"这里换话题了"。
   * 一个段落超过长度上限时，才在句末标点处再切——不按字符数无条件切，因为中文没有
   * 空格，硬切正好落在词中间时，切出来的两段都答不上任何问题。
   */
  static List<String> splitSegments(String text) {
    List<String> out = new ArrayList<>();
    for (String paragraph : text.split("\\n\\s*\\n")) {
      String p = paragraph.trim();
      if (p.isEmpty()) {
        continue;
      }
      if (p.length() > SEGMENT_CHARS) {
        out.addAll(hardWrap(p));
        continue;
      }
      out.add(p);
    }
    return out;
  }

  private static List<String> hardWrap(String text) {
    List<String> out = new ArrayList<>();
    int start = 0;
    while (start < text.length()) {
      int end = Math.min(start + SEGMENT_CHARS, text.length());
      if (end < text.length()) {
        int cut = Math.max(
            Math.max(text.lastIndexOf('。', end), text.lastIndexOf('\n', end)),
            Math.max(text.lastIndexOf('；', end), text.lastIndexOf('.', end)));
        // 找不到句末标点就按长度切：切在词中间仍比丢掉后半段好
        if (cut > start + SEGMENT_CHARS / 2) {
          end = cut + 1;
        }
      }
      String piece = text.substring(start, end).trim();
      if (!piece.isEmpty()) {
        out.add(piece);
      }
      start = end;
    }
    return out;
  }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** 单字谓词与泛谓词都不参与冲突消解，只作为普通 fact 存着。 */
    static boolean isGenericPredicate(String predicate) {
        if (predicate == null || predicate.isBlank()) {
            return true;
        }
        String p = predicate.trim().toLowerCase();
        return p.codePointCount(0, p.length()) <= 1 || GENERIC_PREDICATES.contains(p);
    }

    private String json(Map<String, Object> facts) {
        try {
            return mapper.writeValueAsString(facts);
        } catch (Exception e) {
            throw new IllegalArgumentException("facts 无法序列化: " + e.getMessage());
        }
    }

    /** 用 0x1F 拼 hints；库里是 text[]，SQL 侧 string_to_array 拆开。 */
    private String joinHints(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join(String.valueOf((char) 0x1F), values);
    }

    private static OffsetDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
        try {
            return Instant.parse(raw).atOffset(ZoneOffset.UTC);
        } catch (Exception ignored) {
            try {
                return LocalDate.parse(raw).atStartOfDay().atOffset(ZoneOffset.UTC);
            } catch (Exception e) {
                throw new IllegalArgumentException("validFrom 需为 ISO-8601 时间或日期: " + raw);
            }
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 两段说明合并成一句；都为空时返回 null，调用方据此判断「有没有降级」。 */
    private static String join(String first, String second) {
        boolean hasFirst = first != null && !first.isBlank();
        boolean hasSecond = second != null && !second.isBlank();
        if (hasFirst && hasSecond) {
            return first + "；" + second;
        }
        return hasFirst ? first : (hasSecond ? second : null);
    }

    private static String lower(String s, String def) {
        String t = blankToNull(s);
        return t == null ? def : t.toLowerCase(java.util.Locale.ROOT);
    }

    private static String upper(String s, String def) {
        String t = blankToNull(s);
        return t == null ? def : t.toUpperCase(java.util.Locale.ROOT);
    }
}
