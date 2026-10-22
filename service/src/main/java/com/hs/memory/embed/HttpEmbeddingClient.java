package com.hs.memory.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.memory.net.Retry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI 兼容的 /embeddings 客户端（Xinference、Ollama 的 /v1、各类云厂商都吃这一套）。
 *
 * <p>超时是硬约束：召回链路的总预算只有几百毫秒，embedding 慢就整条慢。
 * 超时直接抛异常，由上层降级成「只走 BM25」——少一路召回比整次召回失败好。
 *
 * <p>失败只在「值得重试」时重试一次（429 / 408 / 5xx / 连接超时），见 {@link Retry}。
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
        Failure last = null;
        for (int attempt = 1; attempt <= Retry.ATTEMPTS; attempt++) {
            try {
                return embedOnce(text);
            } catch (Failure e) {
                last = e;
                if (!e.retryable || attempt == Retry.ATTEMPTS) {
                    throw e;
                }
                Retry.backoff();
            }
        }
        throw last;
    }

    private float[] embedOnce(String text) {
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
                throw new Failure("embedding HTTP " + resp.statusCode() + ": " + abbreviate(resp.body()),
                        null, Retry.retryableStatus(resp.statusCode()));
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode vec = root.path("data").path(0).path("embedding");
            if (!vec.isArray() || vec.isEmpty()) {
                throw new Failure("embedding 响应里没有 data[0].embedding", null, true);
            }
            if (vec.size() != dims) {
                // 维度不符是配置错，重试一百次也一样，直接失败让人看见。
                throw new Failure("embedding 维度不符：期望 " + dims + "，实际 " + vec.size()
                        + "（改了模型就必须同步 EMBEDDING_DIMS，并重建 Qdrant 集合）", null, false);
            }
            float[] out = new float[dims];
            for (int i = 0; i < dims; i++) {
                out[i] = (float) vec.get(i).asDouble();
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("embedding 被中断", e, false);
        } catch (HttpTimeoutException e) {
            throw new Failure("embedding 超时（" + timeout.toMillis() + "ms）", e, true);
        } catch (IOException e) {
            throw new Failure("embedding 调用失败: " + e.getMessage(), e, true);
        } catch (Failure e) {
            throw e;
        } catch (Exception e) {
            throw new Failure("embedding 调用失败: " + e.getMessage(), e, false);
        }
    }

    /** 带「值不值得重试」标记的失败。对外仍以 IllegalStateException 呈现。 */
    private static final class Failure extends IllegalStateException {
        private final boolean retryable;

        Failure(String message, Throwable cause, boolean retryable) {
            super(message, cause);
            this.retryable = retryable;
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
