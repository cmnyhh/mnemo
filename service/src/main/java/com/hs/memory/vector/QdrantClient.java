package com.hs.memory.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hs.memory.Settings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Qdrant REST 客户端。刻意不引官方 SDK：这里只用到 5 个接口，
 * 多一个依赖就多一份和 JDK 21 / Jackson 版本打架的可能。
 *
 * <p>集合参数（4096 维、向量落盘、HNSW 留内存）由 {@link #ensureCollection()} 保证，
 * 与 README 里那张表一致。维度不一致时不重建、只报错——重建等于静默丢向量。
 */
public class QdrantClient {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String collection;
    private volatile int dims;
    private final boolean wait;
    private final Duration timeout;

    public QdrantClient(Settings s) {
        String url = s.qdrantUrl();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.baseUrl = url;
        this.apiKey = s.qdrantApiKey() == null ? "" : s.qdrantApiKey().trim();
        this.collection = s.qdrantCollection();
        this.dims = s.embeddingDims();
        this.wait = s.qdrantWait();
        this.timeout = Duration.ofMillis(s.qdrantTimeoutMs());
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(s.qdrantTimeoutMs(), 2000)))
                .build();
    }

    /** @return true 表示集合可用且维度匹配 */
    public boolean ensureCollection() {
        try {
            Integer existing = collectionDims();
            if (existing != null) {
                if (existing > 0 && existing != dims) {
                    throw new IllegalStateException("Qdrant 集合 " + collection + " 维度是 " + existing
                            + "，配置是 " + dims + "。改维度必须重建集合并重算 embedding（控制台「重建索引」）。");
                }
                ensurePayloadIndexes();
                return true;
            }
            createCollection();
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant 集合初始化失败: " + e.getMessage(), e);
        }
    }

    /** @return 集合当前的向量维度；集合不存在或读不到时返回 null */
    public Integer collectionDims() {
        JsonNode existing = get("/collections/" + collection);
        if (existing == null) {
            return null;
        }
        int size = existing.path("result").path("config").path("params").path("vectors").path("size").asInt(-1);
        return size > 0 ? size : null;
    }

    /** 配置里改了维度：下次 ensureCollection 按新维度判断。 */
    public void setDims(int dims) {
        this.dims = dims;
    }

    public int dims() {
        return dims;
    }

    /**
     * 删掉重建。只给「重建索引」用：调用方紧接着会把所有活着的记录重新写入，
     * 所以这里不做「先备份」——向量本来就是可从 PG 重算的派生数据。
     */
    public void recreateCollection(int newDims) {
        try {
            send("DELETE", "/collections/" + collection, Map.of());
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("HTTP 404")) {
                throw e;
            }
        }
        this.dims = newDims;
        createCollection();
    }

    private void createCollection() {
        Map<String, Object> vectors = new LinkedHashMap<>();
        vectors.put("size", dims);
        vectors.put("distance", "Cosine");
        // 向量本体落盘交给 page cache：4G 机器省 400MB+，且内核可回收，抗 OOM
        vectors.put("on_disk", true);

        Map<String, Object> hnsw = new LinkedHashMap<>();
        hnsw.put("on_disk", false); // 图很小（200B/点），必须留内存
        hnsw.put("m", 16);
        hnsw.put("ef_construct", 128);

        Map<String, Object> optimizers = new LinkedHashMap<>();
        optimizers.put("memmap_threshold", 20000);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vectors", vectors);
        body.put("hnsw_config", hnsw);
        body.put("optimizers_config", optimizers);

        send("PUT", "/collections/" + collection, body);
        ensurePayloadIndexes();
    }

    private void ensurePayloadIndexes() {
        // 过滤前置靠这些索引；失败只告警（重复创建会 409）
        indexField("tenant_id", "keyword");
        indexField("project_id", "keyword");
        indexField("status", "keyword");
        indexField("kind", "keyword");
    }

    private void indexField(String field, String schema) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("field_name", field);
            body.put("field_schema", schema);
            send("PUT", "/collections/" + collection + "/index?wait=false", body);
        } catch (Exception ignored) {
            // 已存在时 Qdrant 返回 409；不影响功能
        }
    }

    public void upsert(UUID id, float[] vector, Map<String, Object> payload) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", id.toString());
        point.put("vector", vector);
        point.put("payload", payload);
        Map<String, Object> body = Map.of("points", List.of(point));
        send("PUT", "/collections/" + collection + "/points?wait=" + wait, body);
    }

    public List<PointHit> query(float[] vector, int limit, Map<String, Object> filter, double minScore) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", vector);
        body.put("limit", limit);
        body.put("with_payload", false);
        body.put("with_vector", false);
        if (minScore > 0) {
            // 在 Qdrant 侧就滤掉低相似度，别让它们在 RRF 里靠「名次」拿到高分
            body.put("score_threshold", minScore);
        }
        if (filter != null && !filter.isEmpty()) {
            body.put("filter", filter);
        }
        JsonNode root = send("POST", "/collections/" + collection + "/points/query", body);
        List<PointHit> hits = new ArrayList<>();
        for (JsonNode p : root.path("result").path("points")) {
            try {
                hits.add(new PointHit(UUID.fromString(p.path("id").asText()), p.path("score").asDouble()));
            } catch (IllegalArgumentException ignored) {
                // 非 uuid 的点不是本服务写的，跳过
            }
        }
        return hits;
    }

    public long count() {
        Map<String, Object> body = Map.of("exact", true);
        JsonNode root = send("POST", "/collections/" + collection + "/points/count", body);
        return root.path("result").path("count").asLong(0);
    }

    public void delete(List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        List<String> points = new ArrayList<>();
        for (UUID id : ids) {
            points.add(id.toString());
        }
        send("POST", "/collections/" + collection + "/points/delete?wait=" + wait,
                Map.of("points", points));
    }

    /**
     * 只改 payload，不动向量。分层治理改层后同步用。
     *
     * <p>wait=false：这是尽力而为之的同步，PG 才是层的权威；为它多等一次写入不划算。
     */
    public void setPayload(List<UUID> ids, Map<String, Object> payload) {
        if (ids.isEmpty() || payload.isEmpty()) {
            return;
        }
        List<String> points = new ArrayList<>();
        for (UUID id : ids) {
            points.add(id.toString());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("payload", payload);
        body.put("points", points);
        send("POST", "/collections/" + collection + "/points/payload?wait=false", body);
    }

    public boolean ping() {
        try {
            get("/collections/" + collection);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 过滤条件构造器：tenant/status 必带，project 可选。 */
    public static Map<String, Object> filter(String tenantId, String projectId, String status) {
        return filter(tenantId, projectId, status, List.of());
    }

    /**
     * 带层级的过滤。tiers 非空时用 {@code match.any} 在向量侧就挡掉不该出现的层，
     * 省得把归档条目取回来再在 Java 里丢——那等于用 topK 的名额装垃圾。
     */
    public static Map<String, Object> filter(String tenantId, String projectId, String status,
                                             List<String> tiers) {
        List<Map<String, Object>> must = new ArrayList<>();
        must.add(match("tenant_id", tenantId));
        if (status != null) {
            must.add(match("status", status));
        }
        if (projectId != null && !projectId.isBlank()) {
            must.add(match("project_id", projectId));
        }
        if (tiers != null && !tiers.isEmpty()) {
            must.add(anyOf("tier", tiers));
        }
        return Map.of("must", must);
    }

    private static Map<String, Object> match(String key, String value) {
        return Map.of("key", key, "match", Map.of("value", value));
    }

    private static Map<String, Object> anyOf(String key, List<String> values) {
        return Map.of("key", key, "match", Map.of("any", values));
    }

    private JsonNode get(String path) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .GET();
            if (!apiKey.isEmpty()) {
                b.header("api-key", apiKey);
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) {
                return null;
            }
            if (r.statusCode() / 100 != 2) {
                throw new IllegalStateException("Qdrant " + path + " HTTP " + r.statusCode() + ": " + r.body());
            }
            return r.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(r.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Qdrant 不可达: " + e.getMessage(), e);
        }
    }

    private JsonNode send(String method, String path, Object body) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("content-type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json));
            if (!apiKey.isEmpty()) {
                b.header("api-key", apiKey);
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new IllegalStateException("Qdrant " + path + " HTTP " + r.statusCode() + ": " + r.body());
            }
            return r.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(r.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Qdrant 请求失败: " + e.getMessage(), e);
        }
    }

    public record PointHit(UUID id, double score) {
    }

    /** 供 /readyz 用，不暴露 URI 之外的信息。 */
    public String describe() {
        return baseUrl + "/collections/" + collection;
    }
}
