package com.hs.memory.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI / Cohere / Jina / TEI 风格的通吃精排客户端。
 *
 * <p>请求：{@code POST {base}/rerank}，body 为 {@code {model, query, documents, top_n}}。
 * 响应里每条是 {@code {index, relevance_score}}，TEI 把分数叫 {@code score}，两个都认。
 * 有的实现不返回 index、只按顺序给——那种情况按数组顺序对回去。
 *
 * <p>超时按硬预算处理：宁可这一轮不精排，也不能让召回整体超时。
 */
public class HttpReranker implements Reranker {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final int maxDocs;

    public HttpReranker(String baseUrl, String apiKey, String model, int timeoutMs, int maxDocs) {
        this.endpoint = trimTrailingSlash(baseUrl) + "/rerank";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.maxDocs = maxDocs;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 2000)))
                .build();
    }

    @Override
    public double[] score(String query, List<String> docs) throws RerankException {
        if (docs.isEmpty()) {
            return new double[0];
        }
        int n = Math.min(docs.size(), maxDocs);
        List<String> batch = docs.subList(0, n);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("query", query);
            body.put("documents", batch);
            body.put("top_n", n);
            body.put("return_documents", false);

            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (!apiKey.isEmpty()) {
                req.header("authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new RerankException("rerank HTTP " + resp.statusCode() + ": " + abbreviate(resp.body()));
            }
            return parse(resp.body(), n);
        } catch (RerankException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RerankException("rerank 被中断", e);
        } catch (Exception e) {
            throw new RerankException("rerank 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把响应摊回与文档等长的数组。缺失的条目给 0（宁可当它不相关，也不要给它虚假的高分）。
     */
    double[] parse(String body, int docsCount) throws RerankException {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                throw new RerankException("rerank 响应里没有 results 数组: " + abbreviate(body));
            }
            double[] out = new double[docsCount];
            int positional = 0;
            for (JsonNode r : results) {
                int idx = r.has("index") ? r.path("index").asInt(-1) : positional;
                positional++;
                if (idx < 0 || idx >= docsCount) {
                    continue;
                }
                JsonNode scoreNode = r.has("relevance_score") ? r.path("relevance_score") : r.path("score");
                if (!scoreNode.isNumber()) {
                    continue;
                }
                out[idx] = clamp01(scoreNode.asDouble());
            }
            return out;
        } catch (RerankException e) {
            throw e;
        } catch (Exception e) {
            throw new RerankException("rerank 响应解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String describe() {
        return "http model=" + model + " timeout=" + timeout.toMillis() + "ms";
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0;
        }
        return Math.max(0, Math.min(1, v));
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
