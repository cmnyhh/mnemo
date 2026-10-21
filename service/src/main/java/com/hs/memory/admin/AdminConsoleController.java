package com.hs.memory.admin;

import com.hs.memory.Settings;
import com.hs.memory.api.SearchRequest;
import com.hs.memory.api.SearchResponse;
import com.hs.memory.asset.AssetService;
import com.hs.memory.config.RuntimeConfig;
import com.hs.memory.embed.EmbeddingClient;
import com.hs.memory.embed.HashEmbeddingClient;
import com.hs.memory.embed.HttpEmbeddingClient;
import com.hs.memory.repo.AdminRecord;
import com.hs.memory.repo.MemoryRepository;
import com.hs.memory.rerank.HttpReranker;
import com.hs.memory.rerank.Reranker;
import com.hs.memory.search.SearchService;
import com.hs.memory.tier.TierService;
import com.hs.memory.vector.QdrantClient;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台的数据与配置端点。
 *
 * <p>所有端点都在 {@link AdminFilter} 后面：没有会话拿不到任何一条记忆。
 * 租户固定取服务端配置，不接受请求参数——控制台是单租户的运维视图，
 * 让它能切租户，等于把「越权查看」做成了一个下拉框。
 */
@RestController
@RequestMapping("/admin/api")
public class AdminConsoleController {

    private static final long EMBEDDING_PROBE_TTL_MS = 60_000;

    private final RuntimeConfig config;
    private final MemoryRepository repo;
    private final SearchService search;
    private final QdrantClient qdrant;
    private final ReindexService reindex;
    private final AdminAuthService auth;
    private final Settings settings;
    private final JdbcTemplate jdbc;
    private final TierService tiers;
    private final AssetService assets;
    private final com.hs.memory.obs.AuditService audit;
    private final String version;

    private volatile long probeAt;
    private volatile Map<String, Object> probe;

    public AdminConsoleController(RuntimeConfig config, MemoryRepository repo, SearchService search,
                                  QdrantClient qdrant, ReindexService reindex, AdminAuthService auth,
                                  Settings settings, JdbcTemplate jdbc, TierService tiers,
                                  AssetService assets, com.hs.memory.obs.AuditService audit,
                                  ObjectProvider<BuildProperties> buildInfo) {
        this.config = config;
        this.repo = repo;
        this.search = search;
        this.qdrant = qdrant;
        this.reindex = reindex;
        this.auth = auth;
        this.settings = settings;
        this.jdbc = jdbc;
        this.tiers = tiers;
        this.assets = assets;
        this.audit = audit;
        BuildProperties build = buildInfo.getIfAvailable();
        // 本地跑单测 / 未走 package 时没有 build-info，显示 dev 而不是假的版本号
        this.version = build == null ? "dev" : build.getVersion();
    }

    // ------------------------------------------------------------------ 概览

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        String tenant = settings.tenant();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", serviceInfo());

        Map<String, Object> stats = repo.stats(tenant);
        out.put("stats", stats);

        Map<String, Object> vector = new LinkedHashMap<>();
        Integer dims = null;
        long points = -1;
        String vectorError = null;
        try {
            dims = qdrant.collectionDims();
            points = qdrant.count();
        } catch (Exception e) {
            vectorError = e.getMessage();
        }
        vector.put("collection", settings.qdrantCollection());
        vector.put("dims", dims);
        vector.put("points", points);
        vector.put("error", vectorError);
        out.put("vector", vector);

        Map<String, Object> space = config.space();
        out.put("space", space);
        out.put("requiresReindex", config.requiresReindex());
        out.put("reindex", reindex.state());
        out.put("recentSessions", repo.sessions(tenant, 5));
        out.put("configRevision", config.revision());
        // 分层与附件分布：控制台首页直接看「常驻几条、归档几条、图几张」
        Map<String, Object> media = new LinkedHashMap<>();
        media.put("assets", assets.count(tenant));
        media.put("byKind", assets.countByKind(tenant));
        out.put("media", media);
        out.put("tierPolicy", Map.of(
                "workingSetTtlHours", settings.tierWorkingSetTtlHours(),
                "archiveAfterDays", settings.tierArchiveAfterDays(),
                "promoteAccess", settings.tierPromoteAccess(),
                "pinnedBudgetChars", settings.tierPinnedBudgetChars()));
        out.put("adminSessions", auth.sessionCount());
        return out;
    }

    @GetMapping("/dependencies")
    public Map<String, Object> dependencies(@RequestParam(name = "refresh", required = false) String refresh) {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> db = new LinkedHashMap<>();
        try {
            Integer one = jdbc.queryForObject("select 1", Integer.class);
            db.put("ok", one != null && one == 1);
        } catch (Exception e) {
            db.put("ok", false);
            db.put("error", e.getMessage());
        }
        out.put("db", db);

        Map<String, Object> vec = new LinkedHashMap<>();
        try {
            Integer dims = qdrant.collectionDims();
            vec.put("ok", dims != null);
            vec.put("dims", dims);
            vec.put("points", qdrant.count());
        } catch (Exception e) {
            vec.put("ok", false);
            vec.put("error", e.getMessage());
        }
        out.put("qdrant", vec);

        out.put("embedding", probeEmbedding(refresh != null && !refresh.isBlank()));
        return out;
    }

    // ------------------------------------------------------------------ 记录

    @GetMapping("/records")
    public Map<String, Object> records(
            @RequestParam(name = "session", required = false) String session,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "tier", required = false) String tier,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "30") int size) {
        String tenant = settings.tenant();
        int pageSize = Math.max(1, Math.min(size, 100));
        int current = Math.max(1, page);
        long total = repo.countRecords(tenant, session, query, status, tier);
        List<AdminRecord> items = repo.listRecords(tenant, session, query, status, tier,
                pageSize, (current - 1) * pageSize);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", current);
        out.put("size", pageSize);
        out.put("pages", total == 0 ? 0 : (total + pageSize - 1) / pageSize);
        out.put("items", items);
        return out;
    }

    @GetMapping("/records/{id}")
    public ResponseEntity<Map<String, Object>> record(@PathVariable String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("id 需为 uuid");
        }
        return repo.record(settings.tenant(), uuid)
                .<ResponseEntity<Map<String, Object>>>map(r -> ResponseEntity.ok(Map.of("record", r)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "记录不存在")));
    }

    /** 软删：与 /v1/memory/forget 同一语义，控制台只是换了一种鉴权方式。 */
    @PostMapping("/records/{id}/forget")
    public Map<String, Object> forget(@PathVariable String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("id 需为 uuid");
        }
        boolean deleted = repo.softDelete(settings.tenant(), uuid);
        boolean vectorDeleted = false;
        String note = null;
        if (deleted) {
            try {
                qdrant.delete(List.of(uuid));
                vectorDeleted = true;
            } catch (Exception e) {
                note = "PG 已标记删除，向量点删除失败：" + e.getMessage();
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", deleted);
        out.put("vectorDeleted", vectorDeleted);
        if (note != null) {
            out.put("note", note);
        }
        audit.record("record.forget", id, deleted ? "ok" : "not-found",
                Map.of("vectorDeleted", vectorDeleted));
        return out;
    }

    /**
     * 钉住 / 取消钉住。控制台是 T0 的第二个入口（另一个是写入时显式给 tier=T0），
     * 两者都要求「有人明确说要它每轮都在」。
     */
    @PostMapping("/records/{id}/pin")
    public Map<String, Object> pin(@PathVariable String id,
                                   @RequestBody(required = false) Map<String, Object> body) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("id 需为 uuid");
        }
        boolean pinned = body == null || body.get("pinned") == null
                || Boolean.parseBoolean(String.valueOf(body.get("pinned")));
        boolean ok = tiers.pin(settings.tenant(), uuid, pinned);
        audit.record(pinned ? "record.pin" : "record.unpin", id, ok ? "ok" : "not-found", Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", ok);
        out.put("tier", pinned ? "T0" : "T2");
        return out;
    }

    /** 手工跑一次分层治理：改完参数（或升级后）不必等定时任务。 */
    @PostMapping("/tiers/rebalance")
    public TierService.RebalanceResult rebalance() {
        TierService.RebalanceResult result = tiers.rebalance();
        audit.record("tier.rebalance", null, Map.of("result", String.valueOf(result)));
        return result;
    }

    @GetMapping("/pinned")
    public TierService.Pinned pinned() {
        return tiers.pinned(settings.tenant(), null);
    }

    @GetMapping("/sessions")
    public Map<String, Object> sessions() {
        return Map.of("items", repo.sessions(settings.tenant(), 100));
    }

    // ------------------------------------------------------------------ 召回测试

    @PostMapping("/recall")
    public SearchResponse recall(@RequestBody Map<String, Object> body) {
        String query = body.get("query") == null ? "" : String.valueOf(body.get("query"));
        Integer topK = body.get("topK") == null ? null : (int) Double.parseDouble(String.valueOf(body.get("topK")));
        String mode = body.get("mode") == null ? "hybrid" : String.valueOf(body.get("mode"));
        // 控制台的召回测试可以指定层级：验证「归档条目默认出不来」需要这个开关
        Object rawTiers = body.get("tiers");
        List<String> tiers = rawTiers instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : null;
        return search.search(settings.tenant(), new SearchRequest(query, topK, mode,
                null, null, null, null, null, null, tiers));
    }

    // ------------------------------------------------------------------ 配置

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>(config.describe());
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("collection", settings.qdrantCollection());
        try {
            vector.put("collectionDims", qdrant.collectionDims());
            vector.put("points", qdrant.count());
        } catch (Exception e) {
            vector.put("error", e.getMessage());
        }
        out.put("vector", vector);
        out.put("reindex", reindex.state());
        return out;
    }

    /**
     * 只提交改过的项。键没出现 = 不改，这是密钥字段能安全留空的前提；
     * 值为 null = 删掉覆盖、退回环境变量。
     */
    @PutMapping("/config")
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> body) {
        Object rawValues = body.get("values");
        if (!(rawValues instanceof Map)) {
            throw new IllegalArgumentException("缺少 values 对象");
        }
        long revision = body.get("revision") == null ? 0
                : (long) Double.parseDouble(String.valueOf(body.get("revision")));
        Map<String, Object> patch = new LinkedHashMap<>((Map<String, Object>) rawValues);
        RuntimeConfig.UpdateResult result = config.update(patch, revision);
        // 只记改了哪几个键。值里可能带 apiKey，抄进审计等于把它搬到另一张表。
        audit.record("config.update", null, Map.of("keys", new ArrayList<>(patch.keySet())));
        Map<String, Object> out = new LinkedHashMap<>(config.describe());
        out.put("result", result);
        out.put("reindex", reindex.state());
        return out;
    }

    @PostMapping("/config/test-embedding")
    public Map<String, Object> testEmbedding(@RequestBody Map<String, Object> body) {
        RuntimeConfig.Snapshot snap = config.snapshot();
        String provider = str(body, "provider", snap.embeddingProvider());
        String baseUrl = str(body, "baseUrl", snap.embeddingBaseUrl());
        String model = str(body, "model", snap.embeddingModel());
        int dims = intOf(body, "dims", snap.embeddingDims());
        int timeoutMs = intOf(body, "timeoutMs", snap.embeddingTimeoutMs());
        String apiKey = str(body, "apiKey", "");
        if (apiKey.isEmpty()) {
            // 界面上密钥框是掩码占位，测试时留空表示「用已保存的那把」
            apiKey = snap.embeddingApiKey();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", provider);
        out.put("model", model);
        out.put("dims", dims);
        long t0 = System.nanoTime();
        try {
            EmbeddingClient client = "http".equalsIgnoreCase(provider)
                    ? new HttpEmbeddingClient(baseUrl, apiKey, model, dims, timeoutMs)
                    : new HashEmbeddingClient(dims, model);
            float[] vector = client.embed("连接自检：记忆服务控制台");
            out.put("ok", true);
            out.put("actualDims", vector.length);
            out.put("latencyMs", (System.nanoTime() - t0) / 1_000_000);
            List<Double> head = new ArrayList<>();
            for (int i = 0; i < Math.min(3, vector.length); i++) {
                head.add(Math.round(vector[i] * 10000.0) / 10000.0);
            }
            out.put("sample", head);
            if (vector.length != dims) {
                out.put("warning", "模型实际维度是 " + vector.length + "，与配置的 " + dims + " 不一致");
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("latencyMs", (System.nanoTime() - t0) / 1_000_000);
            out.put("error", e.getMessage());
        }
        return out;
    }

    /**
     * 精排自检：拿一对「相关 / 不相关」的文本，看模型能不能把它们分开。
     *
     * <p>只看「通不通」没用——有些端点会返回 200 但所有分都是 0。真正要看的是 spread：
     * 相关那条明显高于不相关那条，精排才叫可用。
     */
    @PostMapping("/config/test-rerank")
    public Map<String, Object> testRerank(@RequestBody Map<String, Object> body) {
        RuntimeConfig.Snapshot snap = config.snapshot();
        String provider = str(body, "provider", snap.rerankProvider());
        String baseUrl = str(body, "baseUrl", snap.rerankBaseUrl());
        String model = str(body, "model", snap.rerankModel());
        int timeoutMs = intOf(body, "timeoutMs", snap.rerankTimeoutMs());
        int topN = intOf(body, "topN", snap.rerankTopN());
        String apiKey = str(body, "apiKey", "");
        if (apiKey.isEmpty()) {
            apiKey = snap.rerankApiKey();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", provider);
        out.put("model", model);
        if (!"http".equalsIgnoreCase(provider)) {
            out.put("ok", true);
            out.put("skipped", true);
            out.put("note", "当前为 none，召回按 RRF 名次输出，不调用精排");
            return out;
        }

        String query = "记忆服务的向量检索用的是什么数据库";
        List<String> docs = List.of(
                "向量检索使用 Qdrant 存储 4096 维 embedding，余弦相似度",
                "今天午饭吃了红烧肉，味道还不错");
        long t0 = System.nanoTime();
        try {
            Reranker reranker = new HttpReranker(baseUrl, apiKey, model, timeoutMs, topN);
            double[] scores = reranker.score(query, docs);
            out.put("ok", true);
            out.put("latencyMs", (System.nanoTime() - t0) / 1_000_000);
            List<Double> rounded = new ArrayList<>();
            for (double s : scores) {
                rounded.add(Math.round(s * 10000.0) / 10000.0);
            }
            out.put("scores", rounded);
            if (scores.length >= 2) {
                double spread = scores[0] - scores[1];
                out.put("spread", Math.round(spread * 10000.0) / 10000.0);
                if (spread <= 0.05) {
                    out.put("warning", "相关与不相关文本的分数几乎一样（spread≤0.05），这个模型不适合做精排");
                }
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("latencyMs", (System.nanoTime() - t0) / 1_000_000);
            out.put("error", e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------ 重建索引
    @PostMapping("/reindex")
    public ReindexService.State startReindex() {
        ReindexService.State state = reindex.start();
        audit.record("index.reindex", null, Map.of("status", String.valueOf(state.status())));
        return state;
    }

    @GetMapping("/reindex")
    public ReindexService.State reindexState() {
        return reindex.state();
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, Object> serviceInfo() {
        Runtime runtime = Runtime.getRuntime();
        long started = ManagementFactory.getRuntimeMXBean().getStartTime();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", "memory-service");
        out.put("version", version);
        out.put("startedAt", Instant.ofEpochMilli(started).toString());
        out.put("uptimeSec", (System.currentTimeMillis() - started) / 1000);
        out.put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        out.put("heapMaxMb", runtime.maxMemory() / (1024 * 1024));
        out.put("javaVersion", System.getProperty("java.version"));
        out.put("tenant", settings.tenant());
        return out;
    }

    /** 探一次 embedding。60 秒内复用结果：控制台轮询依赖状态时不该反复打模型。 */
    private Map<String, Object> probeEmbedding(boolean force) {
        long now = System.currentTimeMillis();
        Map<String, Object> cached = probe;
        if (!force && cached != null && now - probeAt < EMBEDDING_PROBE_TTL_MS) {
            return cached;
        }
        RuntimeConfig.Snapshot snap = config.snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", snap.embeddingProvider());
        out.put("model", snap.embeddingModel());
        out.put("dims", snap.embeddingDims());
        long t0 = System.nanoTime();
        try {
            float[] vector = config.embedding().embed("健康检查");
            out.put("ok", true);
            out.put("actualDims", vector.length);
            out.put("latencyMs", (System.nanoTime() - t0) / 1_000_000);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("latencyMs", (System.nanoTime() - t0) / 1_000_000);
            out.put("error", e.getMessage());
        }
        probe = out;
        probeAt = now;
        return out;
    }

    private static String str(Map<String, Object> body, String key, String fallback) {
        Object v = body.get(key);
        if (v == null) {
            return fallback;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() && fallback != null && !fallback.isEmpty() ? fallback : s;
    }

    private static int intOf(Map<String, Object> body, String key, int fallback) {
        Object v = body.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
