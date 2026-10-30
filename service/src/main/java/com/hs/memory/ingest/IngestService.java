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
import com.hs.memory.extract.Summarizer;
import com.hs.memory.extract.Summary;
import com.hs.memory.guard.NoiseFilter;
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
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        String batchNote = null;
        // 用户显式说「记住」＝他本人授权。这条路上不做任何内容过滤：
        // 用户要记住的东西长什么样都该收，形状过滤只对付「自动捕获」的那条路。
        boolean explicit = req.turn() != null && "explicit".equalsIgnoreCase(req.turn().trigger());
        if (items != null && !items.isEmpty()) {
            extraction = "structured";
        } else if (req.turn() != null && req.turn().messages() != null && !req.turn().messages().isEmpty()) {
            TurnExtraction extracted = extractTurn(req.turn(), explicit);
            // 排查「这轮为什么没进库」的唯一入口：这条日志同时给出消息构成（谁、多少字）、
            // 走了哪条路、最终产出几条。没有它只能靠猜，上一轮就猜错过一次。
            log.info("turn session={} idx={} trigger={} 消息=[{}] 模式={} 产出={} 说明={}",
                    req.sessionId() != null ? req.sessionId() : req.turn().sessionId(),
                    req.turn().index(), req.turn().trigger(),
                    roleProfile(req.turn()), extracted.mode(), extracted.items().size(),
                    extracted.note());
            if (!extracted.admitted()) {
                // 准入被拒是**有效结论**，不是失败：把理由回给写入方，别让它以为写成功了。
                return new IngestResponse("rejected", 0, 0, List.of(), extracted.note());
            }
            items = extracted.items();
            extraction = extracted.mode();
            batchNote = extracted.note();
        } else {
            throw new IllegalArgumentException("items 与 turn 至少要提供一个");
        }
        if (items == null || items.isEmpty()) {
            // 抽取模型判定「这轮没什么值得记的」是有效结论，不是失败。
            return new IngestResponse(extraction, 0, 0, List.of(), batchNote);
        }

        List<IngestResponse.Result> results = new ArrayList<>();
        int accepted = 0;
        int duplicates = 0;
        int noise = 0;
        for (int i = 0; i < items.size(); i++) {
            IngestRequest.Item current = items.get(i);
            // ★ 不能用 sourceKind 判「要不要过滤」：调用方可以不自报来源，缺省就是
            // manual，工具写入（memory_write）走的正是这条路。所以这里只按内容形状过滤，
            // 唯一豁免的是 trigger=explicit（用户明确要求记住）。
            if (!explicit) {
                // 工具写入允许存 JSON 报文、代码块、令牌这类内容，所以只查脚手架形状；
                // 抽取出来的条目已经是正常文本，同样走这一套即可。
                String reason = NoiseFilter.scaffolding(current.narrative());
                if (reason != null) {
                    // 形状过滤：提示词模板 / 工具回显 / 运行态脚手架。
                    // 它跑在模型之外，所以抽取超时回落的那条路上也照样生效。
                    noise++;
                    metrics.admission("rules");
                    log.info("写入被形状过滤拦下 tenant={} reason={} head={}",
                            tenantId, reason, head(current.narrative()));
                    continue;
                }
            }
            Prepared prepared = prepare(tenantId, tokenId, req, current, i, items.size());
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
                        item.kind(), true, false, join(prepared.note(), persisted.note())));
                continue;
            }
            accepted++;
            String note = join(prepared.note(), embedAndUpsert(item, persisted.id()));
            results.add(new IngestResponse.Result(persisted.id().toString(),
                    item.kind(), false, note == null, note));
        }
        return new IngestResponse(extraction, accepted, duplicates, results,
                noise == 0 ? batchNote
                        : join(batchNote, noise + " 条被形状过滤拦下（提示词模板 / 工具回显 / 运行态脚手架）"));
    }

    /** 待写条目 + 写入审查留下的说明（没有任何拦改时为 null）。 */
    private record Prepared(NewItem item, String note) {
    }

    /**
     * {@code superseded} 是被这条顶掉的旧事实，供调用方同步向量 payload；
     * {@code note} 是「没写入」的原因，{@code duplicate} 为 true 时一定有值。
     */
    private record Persisted(UUID id, boolean duplicate, UUID superseded, String note) {
    }

    /**
     * 抽取结果 + 本轮走了哪条路（回给调用方，便于判断记忆为什么没进来）。
     *
     * @param admitted false 表示模型判定这轮不该进记忆，{@code note} 里是理由
     */
    private record TurnExtraction(List<IngestRequest.Item> items, String mode, boolean admitted,
                                  String note) {
    }

    /**
     * 把一轮对话变成待写条目。两条产出分开算账：
     *
     * <ul>
     *   <li><b>对话记录</b>——每轮都写，内容由总结模型压成一条「聊了什么」的摘要，
     *       压不出来才回落规则拼原文。它回答「我们聊过什么」，价值来自覆盖率，
     *       所以不该被任何准入判断否掉。</li>
     *   <li><b>事实抽取</b>——只在客户端报了信号（explicit / change / repair）时才花钱调模型。
     *       它回答「什么是长期成立的」，模型的 admit 只决定这一半。</li>
     * </ul>
     *
     * <p>以前这两件事共用一个 {@code admit}：模型判「这轮没什么可记的」，整轮就被丢掉，
     * 于是普通聊天永远查不到。记录和事实的判据本来就不同，不该共用一个开关。
     */
    private TurnExtraction extractTurn(IngestRequest.Turn turn, boolean explicit) {
        String joined = joinTurn(turn);
        // joined 为空 ⇒ 这轮只有宿主塞进来的脚手架（系统提示、人设、工具回显），
        // 一句「谁说了什么」都没有。这时调模型只会得到「这是提示词模板」的拒收结论，
        // 白付一次 7~10 秒的调用，所以直接走日志那条路（它同样会判为空并跳过）。
        //
        // 显式写入也走这条路：用户说「记住」但这一轮里一句对话都没有，那就没有可记的东西。
        // 例外它只豁免「准入判断」，不豁免「有没有内容」——放过去只会让模型对着一段
        // 「用户要求记住下面的内容」的前缀凭空编一条。
        if (joined.isBlank() || (!explicit && !hasSignal(turn))) {
            Logged logged = logTurn(turn);
            if (logged.items().isEmpty()) {
                metrics.admission("rules");
                return new TurnExtraction(List.of(), "log-skipped", true,
                        "filtered".equals(logged.mode())
                                ? "本轮没有可记的对话内容（只有宿主脚手架，或用户侧没有实际内容）"
                                : "本轮只有系统/工具消息，没有可记的对话内容");
            }
            metrics.admission("log");
            return new TurnExtraction(logged.items(), modeOf(logged), true,
                    "summary".equals(logged.mode())
                            ? "已记对话摘要（本轮无变更信号，未抽事实）"
                            : "摘要模型不可用，已按原文记对话日志（未抽事实）");
        }
        Extractor extractor = config.extractor();
        if (!extractor.available()) {
            return summaryOnly(turn, "未配置抽取模型");
        }
        try {
            long stage = System.nanoTime();
            ExtractResult result;
            try {
                result = extractor.extract(explicit ? explicitPrefix() + joined : joined);
            } finally {
                metrics.ingestStage("extract", System.nanoTime() - stage);
            }
            // 显式写入只有模型抽不出来（items 空）才会丢，不会因为准入判断被整段否掉。
            if (!result.admitted() && !explicit) {
                // 准入被拒：模型判定这段不适合沉淀成事实。记录照写——被拒的是「抽取」，不是「记录」。
                // 与「抽到 0 条」分开计数，否则「上游发错东西」会被当成「抽取太保守」。
                metrics.admission("reject");
                log.info("准入拒绝 model={} reason={} latency={}ms（对话记录仍然保留）",
                        result.model(), result.reason(), result.latencyMs());
                Logged logged = withLog(turn, List.of(), result.summary());
                return new TurnExtraction(logged.items(),
                        logged.items().isEmpty() ? "admission-rejected" : modeOf(logged), true,
                        "未抽成事实（" + result.reason() + "），仅记对话记录");
            }
            metrics.admission("keep");
            log.info("抽取完成 model={} items={} latency={}ms reason={}",
                    result.model(), result.count(), result.latencyMs(), result.reason());
            return new TurnExtraction(withLog(turn, toItems(result), result.summary()).items(),
                    "llm-extract", true, null);
        } catch (ExtractException e) {
            // 抽取失败不再直接硬切：先试试摘要，成功的话这一轮是「一条记录」而不是「十二段原文」。
            // 这条路上没有事实产出，值得单独计数。
            metrics.degrade("extract");
            log.warn("抽取失败，改用摘要: {}", e.getMessage());
            return summaryOnly(turn, "抽取模型不可用");
        }
    }

    /**
     * 抽取不可用时的退路：至少留下一条对话记录。
     *
     * <p>以前这里直接 {@link #fromTurn} 硬切原文，结果是「模型一挂，库里全是碎段原文」。
     * 摘要与抽取共用同一个模型，所以它很可能也在挂——但值得再试一次：失败只多一次 HTTP，
     * 成功就把十二段碎原文换成一条记录。
     */
    private TurnExtraction summaryOnly(IngestRequest.Turn turn, String why) {
        Logged logged = logTurn(turn);
        if (!logged.items().isEmpty()) {
            return new TurnExtraction(logged.items(), modeOf(logged), true,
                    why + ("summary".equals(logged.mode()) ? "，已记对话摘要" : "，已按原文记对话日志"));
        }
        List<IngestRequest.Item> segments = fromTurn(turn);
        return new TurnExtraction(segments, "episode-fallback", true,
                why + "，摘要也不可用，按段硬切（已过滤提示词模板与工具回显）");
    }

    /** 日志产出的模式名，回给写入方并进指标：用来回答「这一轮记的到底是摘要还是原文」。 */
    private static String modeOf(Logged logged) {
        return "summary".equals(logged.mode()) ? "log-summary" : "summary-fallback";
    }

    /** 客户端是否报了「本轮发生过什么」。没有信号就只记摘要，不花钱抽事实。 */
    private static boolean hasSignal(IngestRequest.Turn turn) {
        return ConversationLog.hasSignal(turn.trigger());
    }

    /** 一轮对话的日志产出：条目 + 它是「模型摘要」还是「原文回退」。 */
    private record Logged(List<IngestRequest.Item> items, String mode) {
    }

    /**
     * 对话记录：先让总结模型把这轮压成一条摘要，压不出来才回落原文拼接。
     *
     * <p>为什么不再直接存原文：原文是逐字转录，库里躺的是「用户：…… 助手：……」这种对话壳，
     * 检索命中的也是壳；一轮七八条消息还各占一份索引。摘要把一轮收敛成一条带结论的记录，
     * 回答的正是「我们聊过什么」。代价是每轮一次模型调用——这是明确选择过的取舍：
     * 宁可慢一点，也要让入记忆的是「聊了什么」，不是「原话长什么样」。
     *
     * <p>但摘要失败不允许丢记录：模型不可用、超时、给空摘要，一律回落
     * {@link ConversationLog#compose}（规则拼原文，确定性），一条都不少。
     *
     * <p>形状过滤挡在摘要之前：先判断「整轮是不是宿主塞的脚手架」，命中就既不摘也不存——
     * 让模型去总结一段系统提示，等于花钱把提示词改写一遍写进记忆。
     */
    private Logged logTurn(IngestRequest.Turn turn) {
        String raw = ConversationLog.compose(turn.messages());
        if (raw.isEmpty()) {
            return new Logged(List.of(), "none");
        }
        // 用宽松档：原文里出现代码块、JSON、令牌都是正常的，不该因此丢掉。
        // 要挡的只有「整轮都是宿主塞进来的脚手架」——伪 XML、工具回显、【…】运行态块。
        // 提示词模板不在这里查：它已经按单条消息逐条剔过了（见 joinTurn / ConversationLog），
        // 整轮再判一次会把「用户贴一段提示词问有什么问题」这类正当对话一起丢掉。
        String reason = NoiseFilter.turnNoise(raw);
        if (reason != null) {
            log.info("对话记录被形状过滤拦下 reason={} head={}", reason, head(raw));
            return new Logged(List.of(), "filtered");
        }
        // 剥完信封几乎没有字，说明这轮没人开口（群里被唤醒却没人接话）。
        // 判据只回答「有没有人说话」，不回答「值不值得记」——后者交给抽取模型。
        // 有信号轮不走这条：那走的是抽取，短句「端口改成 9090」正是最该留的。
        if (!hasSignal(turn)) {
            int userChars = ConversationLog.userChars(turn.messages());
            if (userChars < ConversationLog.MIN_USER_CHARS) {
                log.info("对话没有实质内容（用户侧 {} 字），不记 head={}", userChars, head(raw));
                return new Logged(List.of(), "filtered");
            }
        }
        Summarizer summarizer = config.summarizer();
        if (summarizer.available()) {
            try {
                long stage = System.nanoTime();
                Summary summary;
                try {
                    summary = summarizer.summarize(raw);
                } finally {
                    metrics.ingestStage("summarize", System.nanoTime() - stage);
                }
                if (summary.usable()) {
                    log.info("对话摘要 model={} chars={} keywords={} latency={}ms",
                            summary.model(), summary.text().length(), summary.keywords().size(),
                            summary.latencyMs());
                    return new Logged(
                            List.of(logItem(summary.text(), summary.keywords(), turn.index())), "summary");
                }
            } catch (ExtractException e) {
                metrics.degrade("summarize");
                log.warn("对话摘要失败，回落原文: {}", e.getMessage());
            }
        }
        // 回退才收长度：raw 是喂模型的宽版本（可达 8000 字），直接入库会挤掉整轮注入预算。
        return new Logged(List.of(logItem(ConversationLog.condense(raw), List.of(), turn.index())), "raw");
    }

    /**
     * 一条对话记录。importance 压到 0.3：分层治理按 importance &lt; 0.5 归档，
     * 否则每轮一条，几个月后整个库都是聊天记录。
     */
    private IngestRequest.Item logItem(String narrative, List<String> hints, Integer turnIndex) {
        return new IngestRequest.Item("episode", "project", null, narrative, Map.of(),
                hints, null, null, null, 0.3, 0.6, "medium", null, null, "turn", turnIndex);
    }

    /**
     * 有信号轮：记录 + 抽出来的事实一起写。记录放前面，读起来才是「先这轮，后结论」。
     *
     * <p>抽取那一次调用已经顺带给了摘要，就不再打第二次模型；它没给（旧模型、畸形输出）
     * 才回落 {@link #logTurn}。
     */
    private Logged withLog(IngestRequest.Turn turn, List<IngestRequest.Item> items, String summaryText) {
        Logged logged = summaryText == null || summaryText.isBlank()
                ? logTurn(turn)
                : new Logged(List.of(logItem(summaryText, List.of(), turn.index())), "summary");
        if (logged.items().isEmpty()) {
            return new Logged(items, logged.mode());
        }
        List<IngestRequest.Item> out = new ArrayList<>(logged.items().size() + items.size());
        out.addAll(logged.items());
        out.addAll(items);
        return new Logged(out, logged.mode());
    }

    private String joinTurn(IngestRequest.Turn turn) {
        StringBuilder sb = new StringBuilder();
        for (IngestRequest.Message m : turn.messages()) {
            if (isScaffoldingRole(m.role())) {
                continue;
            }
            String content = m.content() == null ? "" : m.content().trim();
            if (content.isEmpty()) {
                continue;
            }
            // 抽取也要先剥信封：把三千字的角色设定与表情包策略喂给模型，
            // 它只会判「这是提示词模板」然后整轮拒收，真正群聊的那两句反倒看不到。
            content = Envelope.strip(content);
            if (content.isEmpty()) {
                continue;
            }
            // 同上：role 说是对话、内容却是人设的，只丢这一条。
            if (NoiseFilter.isRolePrompt(content)) {
                continue;
            }
            sb.append(nullSafe(m.role())).append(": ").append(content).append('\n');
        }
        return sb.toString();
    }

    /** 一条 "role:字数" 的清单，用来在日志里一眼看出这轮到底装了什么。 */
    private static String roleProfile(IngestRequest.Turn turn) {
        if (turn.messages() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (IngestRequest.Message m : turn.messages()) {
            if (m == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            String role = m.role() == null || m.role().isBlank() ? "?" : m.role().trim();
            int len = m.content() == null ? 0 : m.content().length();
            sb.append(role).append(':').append(len);
        }
        return sb.toString();
    }

    /**
     * 这些角色的内容是宿主塞给模型的「你现在是什么、你能用什么」，不是谁说的话。
     *
     * <p>它们必须挡在抽取之前：一整套人设加插件说明有几千字，模型的结论只会是
     * 「这是提示词模板，不该进记忆」，于是整轮被拒——而真正的那两句对话反倒从没被看到。
     *
     * <p><b>判据只看 role 字段，不看内容</b>。这是整条写入链上唯一一个不需要猜的地方：
     * 人设和系统提示长什么样没有边界（中文的、英文的、包在标签里的、拼成一行的），
     * 但「它不是 user 也不是 assistant」是结构化事实。所以宁可在这里把角色名收全一点，
     * 也不要事后靠正则去认「这段话像不像人设」——那是上一版误杀用户粘贴内容的成因。
     *
     * <p>反过来，这里只列**明确不是对话**的角色：{@code user} / {@code assistant} 及其变体
     * （{@code human} / {@code ai} / {@code model}）不在此列，{@code narrator} 这类
     * 扮演类角色也不列——宁可多记几句，也别把真正说过的话丢掉。
     */
    static boolean isScaffoldingRole(String role) {
        if (role == null || role.isBlank()) {
            // 老客户端不带 role：按对话处理，不能因为缺字段就把整轮丢掉。
            return false;
        }
        return switch (role.trim().toLowerCase(java.util.Locale.ROOT)) {
            // 系统提示 / 人设 / 开发态指令
            case "system", "system_message", "sys", "developer", "developer_message" -> true;
            // 工具调用与回显：工具名、参数、结果都不是谁说的话
            case "tool", "tool_call", "tool_calls", "tool_result", "tool_response",
                    "function", "function_call", "function_result", "observation" -> true;
            default -> false;
        };
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

    /** 先查 idem，再比宾语，最后退休旧事实并插入。顺序错了会把旧事实白白退休掉。 */
    private Persisted persist(NewItem item) {
        return tx.execute(status -> {
            Optional<UUID> existing = repo.findIdByKey(item.tenantId(), item.idemKey());
            if (existing.isPresent()) {
                return new Persisted(existing.get(), true, null, "idempotency key 已存在，未重复写入");
            }
            UUID supersedes = null;
            if ("fact".equals(item.kind()) && item.subjectKey() != null && item.predicate() != null) {
                Optional<MemoryRepository.ActiveFact> active = repo.findActiveFact(item.tenantId(),
                        item.projectId(), item.subjectKey(), item.predicate());
                if (active.isPresent()) {
                    MemoryRepository.ActiveFact old = active.get();
                    // 值没变就不是「新值」，是同一件事换了个说法。退休它等于用一条新描述
                    // 顶掉旧描述，历史白白闭合，而信息一点没增加。
                    if (sameValue(old.objectValue(), item.objectValue())) {
                        return new Persisted(old.id(), true, null, "已有同值事实，未重复写入");
                    }
                    // 泛谓词是多值语义（「支持 X」与「支持 Y」并列），退休会把它们互相顶掉；
                    // 而排他约束又不允许两条 active 并存，所以只能保留先到的那条。
                    if (isGenericPredicate(item.predicate())) {
                        return new Persisted(old.id(), true, null, "泛谓词已有一条事实，未重复写入");
                    }
                    repo.retireFact(old.id());
                    supersedes = old.id();
                }
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
            return inserted.map(id -> new Persisted(id, false, retired, null))
                    .orElseGet(() -> new Persisted(repo.findIdByKey(item.tenantId(), item.idemKey())
                            .orElseThrow(() -> new IllegalStateException("插入后既无新 id 也无既有 id")), true,
                            retired, "idempotency key 已存在，未重复写入"));
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
        String source = resolveSource(item.sourceKind(), req.turn());
        if (!SOURCE.contains(source)) {
            throw new IllegalArgumentException("sourceKind 只能是 turn / tool / manual / import");
        }
        // 用户说「记住」是一种授权，不是一种来源：它由 confidence 抬高来体现（见下），
        // 不能改写 sourceKind。之前这里把 explicit 一律写成 manual，代价是工具写入
        // 在库里和人工写入长得一模一样——事后既数不出「模型写了多少」，也没法按来源回滚。
        // 信任上限不受影响：TrustPolicy 里 manual 与 tool 同为 high。
        boolean explicit = req.turn() != null && "explicit".equalsIgnoreCase(req.turn().trigger());
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
    int seen = 0;
    for (IngestRequest.Message m : turn.messages()) {
        String content = m.content() == null ? "" : m.content().trim();
        if (content.isEmpty()) {
            continue;
        }
        seen++;
        String role = lower(m.role(), "");
        // 工具返回与系统提示不是「对话」：硬切它们只会把日志和提示词写成记忆。
        if ("tool".equals(role) || "system".equals(role) || "function".equals(role)) {
            metrics.admission("rules");
            continue;
        }
        if ("user".equals(role) && isQuestion(content)) {
            continue;
        }
        for (String segment : splitSegments(content)) {
            // 太短的段落（"好的" "收到"）没有可召回的内容，但会长期占着索引。
            if (segment.length() < MIN_SEGMENT_CHARS) {
                continue;
            }
            // 这条兜底路径不经过模型，形状过滤是唯一的闸门。命中就整段丢弃：
            // 把提示词模板切碎了写进去，比这一轮没记住更糟——它会被反复召回注入。
            String reason = NoiseFilter.reason(segment);
            if (reason != null) {
                metrics.admission("rules");
                log.info("兜底切段被形状过滤拦下 reason={} head={}", reason, head(segment));
                continue;
            }
            if (out.size() >= TURN_MAX_SEGMENTS) {
                return out;
            }
            out.add(new IngestRequest.Item("episode", "project", null, segment, Map.of(),
                    null, null, null, null, 0.5, 0.7, "medium", null, null, "turn", null));
        }
    }
    if (seen == 0) {
        throw new IllegalArgumentException("turn.messages 里没有可用的 content");
    }
    // 全部被过滤掉是正常结果（这一轮只剩脚手架），交给上层回 0 条 + 说明。
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

    /**
     * 单字谓词与泛谓词表达的是多值语义（「支持 X」与「支持 Y」并列），不参与冲突消解。
     *
     * <p>但排他约束只允许同 subject + predicate 有一条 active，所以调用方不能「不退休直接插入」
     * ——那会撞约束报错。正确的处理是保留先到的那条，见 {@code persist}。
     */
    static boolean isGenericPredicate(String predicate) {
        if (predicate == null || predicate.isBlank()) {
            return true;
        }
        String p = predicate.trim().toLowerCase(Locale.ROOT);
        return p.codePointCount(0, p.length()) <= 1 || GENERIC_PREDICATES.contains(p);
    }

    /**
     * 两条事实的宾语是不是同一个值。
     *
     * <p>只做形状归一，不做语义判断：让「7800」与「7800（未完成的面板）」判为同一条，
     * 但不试图让「杭州富阳区」与「杭州市富阳区」相等——那是模型该干的事，规则做不到，
     * 硬做只会把不同的值误并。任一侧为空时返回 false：比不了的时候宁可走更新，
     * 也不要静默丢掉新值。
     */
    static boolean sameValue(String a, String b) {
        String x = normalizeValue(a);
        String y = normalizeValue(b);
        return x != null && x.equals(y);
    }

    /**
     * 归一化一个宾语取值：全半角、大小写、括号注释、标点空白。
     *
     * <p>括号里通常是补充说明而不是值本身（「7800（未完成的面板）」→「7800」），所以整块删掉。
     * 返回 null 表示归一化后没有可比较的内容，调用方据此放弃判定。
     */
    static String normalizeValue(String v) {
        if (v == null) {
            return null;
        }
        String s = Normalizer.normalize(v.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        s = s.replaceAll("[（(\\[【][^）)\\]】]*[）)\\]】]", "");
        s = s.replaceAll("[\\p{IsPunctuation}\\s]+", "");
        return s.isEmpty() ? null : s;
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

    /**
     * 显式写入时垫在模型输入前的一句话。
     *
     * <p>没有它，「记住 1asnc@SNCAnujasa」会被准入规则当成随机串否掉——而那正是用户
     * 唯一明确要求记住的东西。准入判断服务于「自动捕获」，不该否决人工指令。
     */
    private static String explicitPrefix() {
        return "【用户已明确要求记住下面的内容：admit 必须为 true，即使是随机串或编码也要原样记下】\n";
    }

    /** 日志里只露个头：整段提示词打进 journal 会把它淹掉。 */
    private static String head(String s) {        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 40 ? t : t.substring(0, 40) + "…";
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

    /**
     * 落库用的来源标签：调用方自报优先，不自报才看请求里有不有 turn。
     *
     * <p>为什么不让 explicit 把来源改写成 manual：来源是<b>归因</b>（这条记录是谁写的），
     * 授权是<b>强度</b>（该不该多信一点）。两者混在一起以后，memory_write 写进来的东西
     * 和人工录入长得一模一样，事后数不出模型写过什么，也没法按来源回滚。
     * 授权强度另有出口——{@code trigger=explicit} 会把 confidence 抬到 0.9，
     * 而 TrustPolicy 给 manual 与 tool 的上限都是 high，所以不改写也不会被压层级。
     */
    static String resolveSource(String declared, IngestRequest.Turn turn) {
        return lower(declared, turn == null ? "manual" : "turn");
    }

    private static String upper(String s, String def) {
        String t = blankToNull(s);
        return t == null ? def : t.toUpperCase(java.util.Locale.ROOT);
    }
}
