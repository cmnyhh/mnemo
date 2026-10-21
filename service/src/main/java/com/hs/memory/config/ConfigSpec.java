package com.hs.memory.config;

import com.hs.memory.Settings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 配置的单一事实来源：键名、类型、范围、分组、帮助文案，以及跨字段校验。
 *
 * <p>基线来自环境变量（{@link Settings}），控制台只覆盖它。这样「容器里写了什么」
 * 与「界面上看到什么」始终能对上：清掉一条覆盖，值就退回环境变量，而不是回到某个
 * 藏在数据库里的出厂默认。排障时这一点比「可恢复默认」重要得多。
 */
public final class ConfigSpec {

    public static final String EMBEDDING_PROVIDER = "embedding.provider";
    public static final String EMBEDDING_BASE_URL = "embedding.base-url";
    public static final String EMBEDDING_API_KEY = "embedding.api-key";
    public static final String EMBEDDING_MODEL = "embedding.model";
    public static final String EMBEDDING_DIMS = "embedding.dims";
    public static final String EMBEDDING_TIMEOUT_MS = "embedding.timeout-ms";

    public static final String BM25_TOP_K = "search.bm25-top-k";
    public static final String DENSE_TOP_K = "search.dense-top-k";
    public static final String RRF_K = "search.rrf-k";
    public static final String DEFAULT_TOP_K = "search.default-top-k";
    public static final String MAX_TOP_K = "search.max-top-k";
    public static final String ABSTAIN_THRESHOLD = "search.abstain-threshold";
    public static final String INJECTION_BUDGET = "search.injection-budget";
    public static final String DENSE_MIN_SCORE = "search.dense-min-score";

    public static final String RERANK_PROVIDER = "rerank.provider";
    public static final String RERANK_BASE_URL = "rerank.base-url";
    public static final String RERANK_API_KEY = "rerank.api-key";
    public static final String RERANK_MODEL = "rerank.model";
    public static final String RERANK_TIMEOUT_MS = "rerank.timeout-ms";
    public static final String RERANK_TOP_N = "rerank.top-n";
    public static final String RERANK_SKIP_GAP = "rerank.skip-gap";

    public static final String EXTRACT_PROVIDER = "extract.provider";
    public static final String EXTRACT_BASE_URL = "extract.base-url";
    public static final String EXTRACT_API_KEY = "extract.api-key";
    public static final String EXTRACT_MODEL = "extract.model";
    public static final String EXTRACT_TIMEOUT_MS = "extract.timeout-ms";
    public static final String EXTRACT_MAX_ITEMS = "extract.max-items";
    public static final String EXTRACT_MIN_CONFIDENCE = "extract.min-confidence";

    private final Map<String, Object> baseline;
    private final List<Def> defs;

    public ConfigSpec(Settings s) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put(EMBEDDING_PROVIDER, s.embeddingProvider());
        b.put(EMBEDDING_BASE_URL, s.embeddingBaseUrl());
        b.put(EMBEDDING_API_KEY, s.embeddingApiKey());
        b.put(EMBEDDING_MODEL, s.embeddingModel());
        b.put(EMBEDDING_DIMS, s.embeddingDims());
        b.put(EMBEDDING_TIMEOUT_MS, s.embeddingTimeoutMs());
        b.put(BM25_TOP_K, s.bm25TopK());
        b.put(DENSE_TOP_K, s.denseTopK());
        b.put(RRF_K, s.rrfK());
        b.put(DEFAULT_TOP_K, s.defaultTopK());
        b.put(MAX_TOP_K, s.maxTopK());
        b.put(ABSTAIN_THRESHOLD, s.abstainThreshold());
        b.put(INJECTION_BUDGET, s.injectionBudgetChars());
        b.put(DENSE_MIN_SCORE, s.denseMinScore());
        b.put(RERANK_PROVIDER, s.rerankProvider());
        b.put(RERANK_BASE_URL, s.rerankBaseUrl());
        b.put(RERANK_API_KEY, s.rerankApiKey());
        b.put(RERANK_MODEL, s.rerankModel());
        b.put(RERANK_TIMEOUT_MS, s.rerankTimeoutMs());
        b.put(RERANK_TOP_N, s.rerankTopN());
        b.put(RERANK_SKIP_GAP, s.rerankSkipGap());
        b.put(EXTRACT_PROVIDER, s.extractProvider());
        b.put(EXTRACT_BASE_URL, s.extractBaseUrl());
        b.put(EXTRACT_API_KEY, s.extractApiKey());
        b.put(EXTRACT_MODEL, s.extractModel());
        b.put(EXTRACT_TIMEOUT_MS, s.extractTimeoutMs());
        b.put(EXTRACT_MAX_ITEMS, s.extractMaxItems());
        b.put(EXTRACT_MIN_CONFIDENCE, s.extractMinConfidence());
        this.baseline = Map.copyOf(b);

        List<Def> d = new ArrayList<>();
        d.add(new Def(EMBEDDING_PROVIDER, ConfigField.Type.ENUM, List.of("hash", "http"),
                null, null, "hash 只够跑通链路，要语义召回请选 http"));
        d.add(new Def(EMBEDDING_BASE_URL, ConfigField.Type.STRING, List.of(), null, null,
                "OpenAI 兼容端点，填到 /v1 为止"));
        d.add(new Def(EMBEDDING_API_KEY, ConfigField.Type.SECRET, List.of(), null, null,
                "内网无鉴权可留空"));
        d.add(new Def(EMBEDDING_MODEL, ConfigField.Type.STRING, List.of(), null, null,
                "与服务端加载的模型名一致"));
        d.add(new Def(EMBEDDING_DIMS, ConfigField.Type.INT, List.of(), 1.0, 65535.0,
                "等于模型原生维度，改后需重建索引"));
        d.add(new Def(EMBEDDING_TIMEOUT_MS, ConfigField.Type.INT, List.of(), 200.0, 30000.0,
                "超时降级为只用关键词召回"));
        d.add(new Def(BM25_TOP_K, ConfigField.Type.INT, List.of(), 1.0, 200.0, "关键词一路取多少条进融合"));
        d.add(new Def(DENSE_TOP_K, ConfigField.Type.INT, List.of(), 1.0, 200.0, "向量一路取多少条进融合"));
        d.add(new Def(RRF_K, ConfigField.Type.INT, List.of(), 1.0, 200.0,
                "越大越弱化名次差距，60 是常用起点"));
        d.add(new Def(DEFAULT_TOP_K, ConfigField.Type.INT, List.of(), 1.0, 50.0, "调用方不传 topK 时返回几条"));
        d.add(new Def(MAX_TOP_K, ConfigField.Type.INT, List.of(), 1.0, 200.0, "调用方最多能要几条"));
        d.add(new Def(ABSTAIN_THRESHOLD, ConfigField.Type.DOUBLE, List.of(), 0.0, 1.0,
                "第一名低于此分就不注入"));
        d.add(new Def(INJECTION_BUDGET, ConfigField.Type.INT, List.of(), 200.0, 8000.0,
                "注入块字符上限"));
        d.add(new Def(DENSE_MIN_SCORE, ConfigField.Type.DOUBLE, List.of(), -1.0, 1.0,
                "低于此值的向量不进融合"));
        d.add(new Def(RERANK_PROVIDER, ConfigField.Type.ENUM, List.of("none", "http"),
                null, null, "http = 调用 {服务地址}/rerank 做精排"));
        d.add(new Def(RERANK_BASE_URL, ConfigField.Type.STRING, List.of(), null, null,
                "OpenAI 兼容端点，填到 /v1 为止"));
        d.add(new Def(RERANK_API_KEY, ConfigField.Type.SECRET, List.of(), null, null,
                "内网无鉴权可留空"));
        d.add(new Def(RERANK_MODEL, ConfigField.Type.STRING, List.of(), null, null,
                "例如 BAAI/bge-reranker-v2-m3"));
        d.add(new Def(RERANK_TIMEOUT_MS, ConfigField.Type.INT, List.of(), 50.0, 5000.0,
                "超时退回关键词与向量的名次"));
        d.add(new Def(RERANK_TOP_N, ConfigField.Type.INT, List.of(), 1.0, 100.0,
                "只精排融合后的前 N 条"));
        d.add(new Def(RERANK_SKIP_GAP, ConfigField.Type.DOUBLE, List.of(), 0.0, 1.0,
                "第一名 RRF 分领先第二名超过此值就跳过精排（0 = 从不跳过）"));
        d.add(new Def(EXTRACT_PROVIDER, ConfigField.Type.ENUM, List.of("none", "http"),
                null, null, "http = 调用 {服务地址}/chat/completions 抽要点"));
        d.add(new Def(EXTRACT_BASE_URL, ConfigField.Type.STRING, List.of(), null, null,
                "OpenAI 兼容端点，填到 /v1 为止"));
        d.add(new Def(EXTRACT_API_KEY, ConfigField.Type.SECRET, List.of(), null, null,
                "内网无鉴权可留空"));
        d.add(new Def(EXTRACT_MODEL, ConfigField.Type.STRING, List.of(), null, null,
                "抽取模型决定记什么，值得挑好的"));
        d.add(new Def(EXTRACT_TIMEOUT_MS, ConfigField.Type.INT, List.of(), 1000.0, 120000.0,
                "抽取在写入路径上，要有上限"));
        d.add(new Def(EXTRACT_MAX_ITEMS, ConfigField.Type.INT, List.of(), 1.0, 30.0,
                "单次最多产出几条"));
        d.add(new Def(EXTRACT_MIN_CONFIDENCE, ConfigField.Type.DOUBLE, List.of(), 0.0, 1.0,
                "低于此置信度的结果直接丢弃"));
        this.defs = List.copyOf(d);
    }

    public Map<String, Object> baseline() {
        return baseline;
    }

    /** 合并：基线 ← 逐条覆盖。null 覆盖视为「删掉这条覆盖」。 */
    public Map<String, Object> merge(Map<String, Object> overrides) {
        Map<String, Object> out = new LinkedHashMap<>(baseline);
        for (Map.Entry<String, Object> e : overrides.entrySet()) {
            if (!baseline.containsKey(e.getKey())) {
                continue;
            }
            if (e.getValue() == null) {
                out.remove(e.getKey());
                out.put(e.getKey(), baseline.get(e.getKey()));
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    public Map<String, Object> coerce(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Def def = def(e.getKey());
            if (def == null) {
                throw new IllegalArgumentException("未知配置项: " + e.getKey());
            }
            out.put(e.getKey(), coerceOne(def, e.getValue()));
        }
        return out;
    }

    private Object coerceOne(Def def, Object raw) {
        if (raw == null) {
            return null;
        }
        switch (def.type()) {
            case ENUM -> {
                String v = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
                if (!def.options().contains(v)) {
                    throw new IllegalArgumentException(def.key() + " 只能是 " + String.join(" / ", def.options()));
                }
                return v;
            }
            case INT -> {
                long v = parseLong(def.key(), raw);
                checkRange(def, (double) v);
                if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException(def.key() + " 超出整数范围");
                }
                return (int) v;
            }
            case DOUBLE -> {
                double v = parseDouble(def.key(), raw);
                checkRange(def, v);
                return v;
            }
            case SECRET, STRING -> {
                String v = String.valueOf(raw).trim();
                boolean isUrl = def.key().equals(EMBEDDING_BASE_URL) || def.key().equals(RERANK_BASE_URL)
                        || def.key().equals(EXTRACT_BASE_URL);
                if (isUrl && !v.isEmpty()
                        && !v.startsWith("http://") && !v.startsWith("https://")) {
                    throw new IllegalArgumentException("服务地址必须以 http:// 或 https:// 开头");
                }
                return v;
            }
            default -> throw new IllegalStateException("未处理的类型: " + def.type());
        }
    }

    private void checkRange(Def def, double v) {
        if (def.min() != null && v < def.min()) {
            throw new IllegalArgumentException(def.key() + " 不能小于 " + trim(def.min()));
        }
        if (def.max() != null && v > def.max()) {
            throw new IllegalArgumentException(def.key() + " 不能大于 " + trim(def.max()));
        }
    }

    /** 跨字段规则。放在这里而不是各字段里，是因为它们描述的是配置整体是否自洽。 */
    public void validate(Map<String, Object> merged) {
        String provider = str(merged, EMBEDDING_PROVIDER);
        String baseUrl = str(merged, EMBEDDING_BASE_URL);
        String model = str(merged, EMBEDDING_MODEL);
        if ("http".equals(provider)) {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("选了 http 就必须填写服务地址，否则每次写入都会降级");
            }
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("选了 http 就必须填写模型名");
            }
        } else if (!"hash".equals(provider)) {
            throw new IllegalArgumentException("嵌入来源只能是 hash / http");
        }
        int dims = num(merged, EMBEDDING_DIMS).intValue();
        if (dims < 1 || dims > 65535) {
            throw new IllegalArgumentException("向量维度必须在 1..65535 之间");
        }
        int defaultTopK = num(merged, DEFAULT_TOP_K).intValue();
        int maxTopK = num(merged, MAX_TOP_K).intValue();
        if (defaultTopK > maxTopK) {
            throw new IllegalArgumentException("默认返回条数不能大于最大返回条数");
        }
        double abstain = num(merged, ABSTAIN_THRESHOLD).doubleValue();
        if (abstain < 0 || abstain > 1) {
            throw new IllegalArgumentException("弃权阈值必须在 0..1 之间");
        }

        String rerankProvider = str(merged, RERANK_PROVIDER);
        if ("http".equals(rerankProvider)) {
            if (str(merged, RERANK_BASE_URL).isBlank()) {
                throw new IllegalArgumentException("选了 http 精排就必须填写服务地址，否则每轮都会降级");
            }
            if (str(merged, RERANK_MODEL).isBlank()) {
                throw new IllegalArgumentException("选了 http 精排就必须填写模型名");
            }
        } else if (!"none".equals(rerankProvider)) {
            throw new IllegalArgumentException("精排来源只能是 none / http");
        }

        String extractProvider = str(merged, EXTRACT_PROVIDER);
        if ("http".equals(extractProvider)) {
            if (str(merged, EXTRACT_BASE_URL).isBlank()) {
                throw new IllegalArgumentException("选了 http 抽取就必须填写服务地址，否则写入会退回按段硬切");
            }
            if (str(merged, EXTRACT_MODEL).isBlank()) {
                throw new IllegalArgumentException("选了 http 抽取就必须填写模型名");
            }
        } else if (!"none".equals(extractProvider)) {
            throw new IllegalArgumentException("抽取来源只能是 none / http");
        }
    }

    /** 渲染给控制台的字段列表，顺序即表单顺序。 */
    public List<ConfigField> fields(Map<String, Object> effective) {
        List<ConfigField> out = new ArrayList<>();
        out.add(ConfigField.enumField(EMBEDDING_PROVIDER, "embedding", "嵌入来源",
                List.of("hash", "http"), str(effective, EMBEDDING_PROVIDER),
                str(baseline, EMBEDDING_PROVIDER), help(EMBEDDING_PROVIDER)));
        out.add(ConfigField.stringField(EMBEDDING_BASE_URL, "embedding", "服务地址",
                str(effective, EMBEDDING_BASE_URL), str(baseline, EMBEDDING_BASE_URL),
                help(EMBEDDING_BASE_URL), "http://10.0.0.5:9997/v1"));
        out.add(ConfigField.secretField(EMBEDDING_API_KEY, "embedding", "API Key",
                str(effective, EMBEDDING_API_KEY), help(EMBEDDING_API_KEY), "sk-…"));
        out.add(ConfigField.stringField(EMBEDDING_MODEL, "embedding", "模型名",
                str(effective, EMBEDDING_MODEL), str(baseline, EMBEDDING_MODEL),
                help(EMBEDDING_MODEL), "qwen3-embedding-8b"));
        out.add(ConfigField.intField(EMBEDDING_DIMS, "embedding", "向量维度",
                intOf(effective, EMBEDDING_DIMS), intOf(baseline, EMBEDDING_DIMS), 1, 65535,
                help(EMBEDDING_DIMS), "4096"));
        out.add(ConfigField.intField(EMBEDDING_TIMEOUT_MS, "embedding", "超时 (ms)",
                intOf(effective, EMBEDDING_TIMEOUT_MS), intOf(baseline, EMBEDDING_TIMEOUT_MS), 200, 30000,
                help(EMBEDDING_TIMEOUT_MS), "3000"));
        out.add(ConfigField.intField(BM25_TOP_K, "search", "词面召回条数",
                intOf(effective, BM25_TOP_K), intOf(baseline, BM25_TOP_K), 1, 200, help(BM25_TOP_K), "40"));
        out.add(ConfigField.intField(DENSE_TOP_K, "search", "向量召回条数",
                intOf(effective, DENSE_TOP_K), intOf(baseline, DENSE_TOP_K), 1, 200, help(DENSE_TOP_K), "40"));
        out.add(ConfigField.intField(RRF_K, "search", "RRF 常数",
                intOf(effective, RRF_K), intOf(baseline, RRF_K), 1, 200, help(RRF_K), "60"));
        out.add(ConfigField.intField(DEFAULT_TOP_K, "search", "默认返回条数",
                intOf(effective, DEFAULT_TOP_K), intOf(baseline, DEFAULT_TOP_K), 1, 50, help(DEFAULT_TOP_K), "6"));
        out.add(ConfigField.intField(MAX_TOP_K, "search", "最大返回条数",
                intOf(effective, MAX_TOP_K), intOf(baseline, MAX_TOP_K), 1, 200, help(MAX_TOP_K), "50"));
        out.add(ConfigField.doubleField(ABSTAIN_THRESHOLD, "search", "弃权阈值",
                numOf(effective, ABSTAIN_THRESHOLD), numOf(baseline, ABSTAIN_THRESHOLD), 0, 1,
                help(ABSTAIN_THRESHOLD)));
        out.add(ConfigField.intField(INJECTION_BUDGET, "search", "注入预算 (字符)",
                intOf(effective, INJECTION_BUDGET), intOf(baseline, INJECTION_BUDGET), 200, 8000,
                help(INJECTION_BUDGET), "1200"));
        out.add(ConfigField.doubleField(DENSE_MIN_SCORE, "search", "向量最低相似度",
                numOf(effective, DENSE_MIN_SCORE), numOf(baseline, DENSE_MIN_SCORE), -1, 1,
                help(DENSE_MIN_SCORE)));
        out.add(ConfigField.enumField(RERANK_PROVIDER, "rerank", "精排来源",
                List.of("none", "http"), str(effective, RERANK_PROVIDER),
                str(baseline, RERANK_PROVIDER), help(RERANK_PROVIDER)));
        out.add(ConfigField.stringField(RERANK_BASE_URL, "rerank", "服务地址",
                str(effective, RERANK_BASE_URL), str(baseline, RERANK_BASE_URL),
                help(RERANK_BASE_URL), "https://api.example.com/v1"));
        out.add(ConfigField.secretField(RERANK_API_KEY, "rerank", "API Key",
                str(effective, RERANK_API_KEY), help(RERANK_API_KEY), "sk-…"));
        out.add(ConfigField.stringField(RERANK_MODEL, "rerank", "模型名",
                str(effective, RERANK_MODEL), str(baseline, RERANK_MODEL),
                help(RERANK_MODEL), "BAAI/bge-reranker-v2-m3"));
        out.add(ConfigField.intField(RERANK_TIMEOUT_MS, "rerank", "超时 (ms)",
                intOf(effective, RERANK_TIMEOUT_MS), intOf(baseline, RERANK_TIMEOUT_MS), 50, 5000,
                help(RERANK_TIMEOUT_MS), "600"));
        out.add(ConfigField.intField(RERANK_TOP_N, "rerank", "精排条数",
                intOf(effective, RERANK_TOP_N), intOf(baseline, RERANK_TOP_N), 1, 100,
                help(RERANK_TOP_N), "12"));
        out.add(ConfigField.doubleField(RERANK_SKIP_GAP, "rerank", "跳过精排的分差",
                numOf(effective, RERANK_SKIP_GAP), numOf(baseline, RERANK_SKIP_GAP), 0, 1,
                help(RERANK_SKIP_GAP)));
        out.add(ConfigField.enumField(EXTRACT_PROVIDER, "extract", "抽取来源",
                List.of("none", "http"), str(effective, EXTRACT_PROVIDER),
                str(baseline, EXTRACT_PROVIDER), help(EXTRACT_PROVIDER)));
        out.add(ConfigField.stringField(EXTRACT_BASE_URL, "extract", "服务地址",
                str(effective, EXTRACT_BASE_URL), str(baseline, EXTRACT_BASE_URL),
                help(EXTRACT_BASE_URL), "https://api.example.com/v1"));
        out.add(ConfigField.secretField(EXTRACT_API_KEY, "extract", "API Key",
                str(effective, EXTRACT_API_KEY), help(EXTRACT_API_KEY), "sk-…"));
        out.add(ConfigField.stringField(EXTRACT_MODEL, "extract", "模型名",
                str(effective, EXTRACT_MODEL), str(baseline, EXTRACT_MODEL),
                help(EXTRACT_MODEL), "Qwen/Qwen2.5-7B-Instruct"));
        out.add(ConfigField.intField(EXTRACT_TIMEOUT_MS, "extract", "超时 (ms)",
                intOf(effective, EXTRACT_TIMEOUT_MS), intOf(baseline, EXTRACT_TIMEOUT_MS),
                1000, 120000, help(EXTRACT_TIMEOUT_MS), "20000"));
        out.add(ConfigField.intField(EXTRACT_MAX_ITEMS, "extract", "单次最多条目",
                intOf(effective, EXTRACT_MAX_ITEMS), intOf(baseline, EXTRACT_MAX_ITEMS),
                1, 30, help(EXTRACT_MAX_ITEMS), "8"));
        out.add(ConfigField.doubleField(EXTRACT_MIN_CONFIDENCE, "extract", "最低置信度",
                numOf(effective, EXTRACT_MIN_CONFIDENCE), numOf(baseline, EXTRACT_MIN_CONFIDENCE),
                0, 1, help(EXTRACT_MIN_CONFIDENCE)));
        return out;
    }

    public Set<String> keys() {
        return new LinkedHashSet<>(baseline.keySet());
    }

    public String help(String key) {
        Def def = def(key);
        return def == null ? "" : def.help();
    }

    public Def def(String key) {
        for (Def d : defs) {
            if (d.key().equals(key)) {
                return d;
            }
        }
        return null;
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    public static Integer intOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return v == null ? null : Integer.valueOf(String.valueOf(v));
    }

    public static Double numOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return v == null ? null : Double.valueOf(String.valueOf(v));
    }

    private static Number num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n;
        }
        throw new IllegalArgumentException(key + " 不是数字");
    }

    private static long parseLong(String key, Object raw) {
        if (raw instanceof Number n) {
            return Math.round(n.doubleValue());
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 需要整数");
        }
    }

    private static double parseDouble(String key, Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 需要数字");
        }
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** 字段定义（不含当前值）。 */
    public record Def(String key, ConfigField.Type type, List<String> options,
                      Double min, Double max, String help) {
    }
}
