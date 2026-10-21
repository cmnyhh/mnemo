package com.hs.memory.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI 兼容的 /embeddings 客户端（Xinference、Ollama 的 /v1、各类云厂商都吃这一套）。
 *
 * <p>超时是硬约束：召回链路的总预算只有 400ms 量级，embedding 慢就整条慢。
 * 超时直接抛异常，由上层降级成「只走 BM25」——少一路召回比整次召回失败好。
 */
public class HttpEmbeddingClient implements EmbeddingClient {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int dims;
    private final Duration timeout;

    public HttpEmbeddingClient(String baseUrl, String apiKey, String model, int dims, int timeoutMs) {
        this.endpoint = trimTrailingSlash(baseUrl) + "/embeddings";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.dims = dims;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 2000)))
                .build();
    }

    @Override
    public float[] embed(String text) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", text);
            body.put("encoding_format", "float");

            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (!apiKey.isEmpty()) {
                req.header("authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("embedding HTTP " + resp.statusCode() + ": " + abbreviate(resp.body()));
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode vec = root.path("data").path(0).path("embedding");
            if (!vec.isArray() || vec.isEmpty()) {
                throw new IllegalStateException("embedding 响应里没有 data[0].embedding");
            }
            if (vec.size() != dims) {
                throw new IllegalStateException(
                        "embedding 维度不符：期望 " + dims + "，实际 " + vec.size()
                                + "（改了模型就必须同步 EMBEDDING_DIMS，并重建 Qdrant 集合）");
            }
            float[] out = new float[dims];
            for (int i = 0; i < dims; i++) {
                out[i] = (float) vec.get(i).asDouble();
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding 被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("embedding 调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int dims() {
        return dims;
    }

    @Override
    public String model() {
        return model;
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
