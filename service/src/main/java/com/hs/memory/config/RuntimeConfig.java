package com.hs.memory.config;

import com.hs.memory.Settings;
import com.hs.memory.embed.EmbeddingClient;
import com.hs.memory.embed.HashEmbeddingClient;
import com.hs.memory.embed.HttpEmbeddingClient;
import com.hs.memory.extract.Extractor;
import com.hs.memory.extract.HttpExtractor;
import com.hs.memory.extract.NoopExtractor;
import com.hs.memory.rerank.HttpReranker;
import com.hs.memory.rerank.NoopReranker;
import com.hs.memory.rerank.Reranker;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 运行期配置：环境变量做基线，控制台写入的覆盖值叠加在上面。
 *
 * <p>为什么不是「改配置就重启容器」：换 embedding 模型这件事本身就要重建向量索引，
 * 重启只会让「改了没生效」和「生效了但数据不对」混在一起。热生效之后，保存、测试、
 * 重建三步可以在一个界面上按顺序做完，每一步的失败点都能单独看见。
 *
 * <p>线程安全：整份快照是不可变对象，读路径无锁；写入串行并带版本号，两个标签页
 * 同时保存时后一个会拿到 409，而不是悄悄覆盖前一个。
 */
@Component
public class RuntimeConfig {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfig.class);
    private static final String STATE_REINDEX_FP = "reindexed.fingerprint";

    private final Settings settings;
    private final ConfigSpec spec;
    private final ConfigStore store;

    private volatile Snapshot snapshot;
    private volatile Map<String, Object> overrides = Map.of();
    private volatile long revision;
    private volatile Instant updatedAt;
    private volatile String reindexedFingerprint;
    private volatile boolean loaded;

    private volatile EmbeddingClient client;
    private volatile String clientKey = "";
    private volatile Reranker reranker;
    private volatile String rerankerKey = "";
    private volatile Extractor extractor;
    private volatile String extractorKey = "";

    public RuntimeConfig(Settings settings, ConfigStore store) {
        this.settings = settings;
        this.spec = new ConfigSpec(settings);
        this.store = store;
        this.snapshot = Snapshot.of(spec.merge(Map.of()));
    }

    public Settings settings() {
        return settings;
    }

    public ConfigSpec spec() {
        return spec;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public Map<String, Object> overrides() {
        return overrides;
    }

    public long revision() {
        return revision;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean loaded() {
        return loaded;
    }

    /**
     * 从数据库重读覆盖值。启动时 schema 建好之后调用一次；保存配置后也调用。
     * 表还不存在时（首次启动、schema 未跑）保持基线，不阻塞启动。
     */
    public synchronized void reload() {
        try {
            ConfigStore.Stored stored = store.load();
            Map<String, Object> known = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : stored.values().entrySet()) {
                if (spec.keys().contains(e.getKey())) {
                    known.put(e.getKey(), e.getValue());
                }
            }
            Map<String, Object> typed = spec.coerce(known);
            Map<String, Object> merged = spec.merge(typed);
            spec.validate(merged);
            this.overrides = Map.copyOf(typed);
            this.revision = stored.revision();
            this.updatedAt = stored.updatedAt();
            this.snapshot = Snapshot.of(merged);
            this.reindexedFingerprint = store.state(STATE_REINDEX_FP);
            this.loaded = true;
            invalidateClient();
            log.info("配置已加载 revision={} space={} provider={} model={} dims={}",
                    revision, snapshot.spaceId(), snapshot.embeddingProvider(),
                    snapshot.embeddingModel(), snapshot.embeddingDims());
        } catch (Exception e) {
            log.warn("配置加载失败，继续使用环境变量基线: {}", e.getMessage());
        }
    }

    /**
     * 保存一组覆盖值。只接受显式给出的键，未给出的保持原样——控制台因此可以只提交
     * 用户真正改过的那几项，密钥字段就不必回传。
     *
     * @param patch            键 → 值；值为 null 表示删掉这条覆盖、退回基线
     * @param expectedRevision 控制台读到的版本号
     */
    public synchronized UpdateResult update(Map<String, Object> patch, long expectedRevision) {
        Map<String, Object> typed = spec.coerce(patch);
        Map<String, Object> next = new HashMap<>(overrides);
        for (Map.Entry<String, Object> e : typed.entrySet()) {
            if (e.getValue() == null) {
                next.remove(e.getKey());
            } else {
                next.put(e.getKey(), e.getValue());
            }
        }
        Map<String, Object> merged = spec.merge(next);
        spec.validate(merged);

        String before = snapshot.fingerprint();
        if (!store.save(next, expectedRevision)) {
            throw new RevisionConflictException("配置已被别处修改，请刷新后重试");
        }
        reload();
        boolean spaceChanged = !before.equals(snapshot.fingerprint());
        return new UpdateResult(revision, spaceChanged, requiresReindex(),
                spaceChanged ? "向量空间已变化，需要重建索引后向量召回才有效" : null);
    }

    public boolean requiresReindex() {
        String fp = reindexedFingerprint;
        return fp == null || !fp.equals(snapshot.fingerprint());
    }

    /** 重建索引成功后调用：记住「当前这个指纹的向量是齐的」。 */
    public void markReindexed() {
        String fp = snapshot.fingerprint();
        store.putState(STATE_REINDEX_FP, fp);
        this.reindexedFingerprint = fp;
    }

    public String reindexedFingerprint() {
        return reindexedFingerprint;
    }

    /** 当前配置对应的 embedding 客户端；配置变了就换一个新的。 */
    public EmbeddingClient embedding() {
        Snapshot s = snapshot;
        String key = s.fingerprint() + "|" + s.embeddingTimeoutMs() + "|" + s.embeddingApiKey();
        EmbeddingClient local = client;
        if (local != null && key.equals(clientKey)) {
            return local;
        }
        synchronized (this) {
            if (client != null && key.equals(clientKey)) {
                return client;
            }
            EmbeddingClient built = build(s);
            this.client = built;
            this.clientKey = key;
            return built;
        }
    }

    private EmbeddingClient build(Snapshot s) {
        if (s.embeddingIsHttp()) {
            if (s.embeddingBaseUrl() == null || s.embeddingBaseUrl().isBlank()) {
                throw new IllegalStateException("嵌入来源是 http，但服务地址为空");
            }
            log.info("embedding: http model={} dims={} base={}",
                    s.embeddingModel(), s.embeddingDims(), s.embeddingBaseUrl());
            return new HttpEmbeddingClient(s.embeddingBaseUrl(), s.embeddingApiKey(),
                    s.embeddingModel(), s.embeddingDims(), s.embeddingTimeoutMs());
        }
        log.warn("embedding: hash（本地确定性向量，无语义能力，仅用于链路自检）");
        return new HashEmbeddingClient(s.embeddingDims(), s.embeddingModel());
    }

    private void invalidateClient() {
        this.client = null;
        this.clientKey = "";
        this.reranker = null;
        this.rerankerKey = "";
        this.extractor = null;
        this.extractorKey = "";
    }

    /** 当前配置对应的精排客户端；未配置时返回 Noop，调用方据此走跳过分支。 */
    public Reranker reranker() {
        Snapshot s = snapshot;
        String key = s.rerankProvider() + "|" + s.rerankBaseUrl() + "|" + s.rerankModel()
                + "|" + s.rerankTimeoutMs() + "|" + s.rerankTopN() + "|" + s.rerankApiKey();
        Reranker local = reranker;
        if (local != null && key.equals(rerankerKey)) {
            return local;
        }
        synchronized (this) {
            if (reranker != null && key.equals(rerankerKey)) {
                return reranker;
            }
            Reranker built = buildReranker(s);
            this.reranker = built;
            this.rerankerKey = key;
            return built;
        }
    }

    private Reranker buildReranker(Snapshot s) {
        if (s.rerankIsHttp()) {
            if (s.rerankBaseUrl() == null || s.rerankBaseUrl().isBlank()) {
                log.warn("rerank: 来源是 http 但服务地址为空，退化为不精排");
                return new NoopReranker();
            }
            log.info("rerank: http model={} topN={} timeout={}ms base={}",
                    s.rerankModel(), s.rerankTopN(), s.rerankTimeoutMs(), s.rerankBaseUrl());
            return new HttpReranker(s.rerankBaseUrl(), s.rerankApiKey(), s.rerankModel(),
                    s.rerankTimeoutMs(), s.rerankTopN());
        }
        return new NoopReranker();
    }

    /** 当前配置对应的抽取器；未配置时返回 Noop，调用方据此走按段硬切的降级路径。 */
    public Extractor extractor() {
        Snapshot s = snapshot;
        String key = s.extractProvider() + "|" + s.extractBaseUrl() + "|" + s.extractModel()
                + "|" + s.extractTimeoutMs() + "|" + s.extractMaxItems()
                + "|" + s.extractMinConfidence() + "|" + s.extractApiKey();
        Extractor local = extractor;
        if (local != null && key.equals(extractorKey)) {
            return local;
        }
        synchronized (this) {
            if (extractor != null && key.equals(extractorKey)) {
                return extractor;
            }
            Extractor built = buildExtractor(s);
            this.extractor = built;
            this.extractorKey = key;
            return built;
        }
    }

    private Extractor buildExtractor(Snapshot s) {
        if (s.extractIsHttp()) {
            if (s.extractBaseUrl() == null || s.extractBaseUrl().isBlank()) {
                log.warn("extract: 来源是 http 但服务地址为空，退回按段硬切");
                return new NoopExtractor();
            }
            log.info("extract: http model={} maxItems={} minConfidence={} base={}",
                    s.extractModel(), s.extractMaxItems(), s.extractMinConfidence(), s.extractBaseUrl());
            return new HttpExtractor(s.extractBaseUrl(), s.extractApiKey(), s.extractModel(),
                    s.extractTimeoutMs(), s.extractMaxItems(), s.extractMinConfidence());
        }
        return new NoopExtractor();
    }

    /** 表单渲染需要：字段元数据 + 当前值 + 基线 + 覆盖集合。 */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revision", revision);
        out.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
        out.put("loaded", loaded);
        out.put("fields", spec.fields(snapshot.merged()));
        out.put("overridden", overrides.keySet().stream().sorted().toList());
        out.put("space", space());
        return out;
    }

    public Map<String, Object> space() {
        Snapshot s = snapshot;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spaceId", s.spaceId());
        out.put("provider", s.embeddingProvider());
        out.put("model", s.embeddingModel());
        out.put("dims", s.embeddingDims());
        out.put("fingerprint", s.fingerprint());
        out.put("reindexedFingerprint", reindexedFingerprint);
        out.put("requiresReindex", requiresReindex());
        return out;
    }

    /** 生效配置的只读快照。 */
    public record Snapshot(
            String embeddingProvider,
            String embeddingBaseUrl,
            String embeddingApiKey,
            String embeddingModel,
            int embeddingDims,
            int embeddingTimeoutMs,
            int bm25TopK,
            int denseTopK,
            int rrfK,
            int defaultTopK,
            int maxTopK,
            double abstainThreshold,
            int injectionBudgetChars,
            double denseMinScore,
            String rerankProvider,
            String rerankBaseUrl,
            String rerankApiKey,
            String rerankModel,
            int rerankTimeoutMs,
            int rerankTopN,
            double rerankSkipGap,
            String extractProvider,
            String extractBaseUrl,
            String extractApiKey,
            String extractModel,
            int extractTimeoutMs,
            int extractMaxItems,
            double extractMinConfidence,
            Map<String, Object> merged) {

        static Snapshot of(Map<String, Object> m) {
            return new Snapshot(
                    ConfigSpec.str(m, ConfigSpec.EMBEDDING_PROVIDER),
                    ConfigSpec.str(m, ConfigSpec.EMBEDDING_BASE_URL),
                    ConfigSpec.str(m, ConfigSpec.EMBEDDING_API_KEY),
                    ConfigSpec.str(m, ConfigSpec.EMBEDDING_MODEL),
                    ConfigSpec.intOf(m, ConfigSpec.EMBEDDING_DIMS),
                    ConfigSpec.intOf(m, ConfigSpec.EMBEDDING_TIMEOUT_MS),
                    ConfigSpec.intOf(m, ConfigSpec.BM25_TOP_K),
                    ConfigSpec.intOf(m, ConfigSpec.DENSE_TOP_K),
                    ConfigSpec.intOf(m, ConfigSpec.RRF_K),
                    ConfigSpec.intOf(m, ConfigSpec.DEFAULT_TOP_K),
                    ConfigSpec.intOf(m, ConfigSpec.MAX_TOP_K),
                    ConfigSpec.numOf(m, ConfigSpec.ABSTAIN_THRESHOLD),
                    ConfigSpec.intOf(m, ConfigSpec.INJECTION_BUDGET),
                    ConfigSpec.numOf(m, ConfigSpec.DENSE_MIN_SCORE),
                    ConfigSpec.str(m, ConfigSpec.RERANK_PROVIDER),
                    ConfigSpec.str(m, ConfigSpec.RERANK_BASE_URL),
                    ConfigSpec.str(m, ConfigSpec.RERANK_API_KEY),
                    ConfigSpec.str(m, ConfigSpec.RERANK_MODEL),
                    ConfigSpec.intOf(m, ConfigSpec.RERANK_TIMEOUT_MS),
                    ConfigSpec.intOf(m, ConfigSpec.RERANK_TOP_N),
                    ConfigSpec.numOf(m, ConfigSpec.RERANK_SKIP_GAP),
                    ConfigSpec.str(m, ConfigSpec.EXTRACT_PROVIDER),
                    ConfigSpec.str(m, ConfigSpec.EXTRACT_BASE_URL),
                    ConfigSpec.str(m, ConfigSpec.EXTRACT_API_KEY),
                    ConfigSpec.str(m, ConfigSpec.EXTRACT_MODEL),
                    ConfigSpec.intOf(m, ConfigSpec.EXTRACT_TIMEOUT_MS),
                    ConfigSpec.intOf(m, ConfigSpec.EXTRACT_MAX_ITEMS),
                    ConfigSpec.numOf(m, ConfigSpec.EXTRACT_MIN_CONFIDENCE),
                    Map.copyOf(m));
        }

        public String spaceId() {
            return embeddingModel + "_" + embeddingDims;
        }

        public boolean embeddingIsHttp() {
            return "http".equalsIgnoreCase(embeddingProvider);
        }

        public boolean rerankIsHttp() {
            return "http".equalsIgnoreCase(rerankProvider);
        }

        public boolean extractIsHttp() {
            return "http".equalsIgnoreCase(extractProvider);
        }

        /**
         * 向量指纹：任何一项变了，旧向量就不能再和新查询向量比较。
         * 只看 model+dims 不够——同一个模型走不同端点，或 hash 换成 http，空间也不同。
         */
        public String fingerprint() {
            return embeddingProvider + "|" + embeddingBaseUrl + "|" + embeddingModel + "|" + embeddingDims;
        }
    }

    public record UpdateResult(long revision, boolean spaceChanged, boolean requiresReindex, String note) {
    }

    /** 两个标签页同时保存时的版本冲突。 */
    public static class RevisionConflictException extends RuntimeException {
        public RevisionConflictException(String message) {
            super(message);
        }
    }

    /** 供测试与诊断：当前覆盖键列表。 */
    public List<String> overrideKeys() {
        return overrides.keySet().stream().sorted().toList();
    }
}
