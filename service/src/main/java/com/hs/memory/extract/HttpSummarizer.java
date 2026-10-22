package com.hs.memory.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容的 /chat/completions 摘要器，与 {@link HttpExtractor} 共用同一份模型配置。
 *
 * <p>为什么不合成一个类：两条 prompt 的输入规模、输出形态、失败代价都不一样。
 * 抽取要严格 JSON（错了会把整轮原文写进记忆），摘要只要能读的一段话就行——
 * 所以这里的解析特意宽松：模型返回的是纯文本、包在 ``` 里、甚至多说了一句「好的」，
 * 都尽量把内容捞出来，而不是整条丢掉。
 */
public class HttpSummarizer implements Summarizer {

    /**
     * 摘要正文上限。
     *
     * <p>提示词允许到 700 字，这里留 100 字余量——模型经常在末尾多写一句收尾。
     * 卡在 600 会把最后那句「未决问题」截掉，而那恰恰是最容易忘的部分。
     */
    static final int MAX_SUMMARY_CHARS = 800;
    /** 送给模型的原文上限。一轮对话超出这个长度时，后半段本来就记不进一条摘要。 */
    static final int MAX_INPUT_CHARS = 8000;

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public HttpSummarizer(String baseUrl, String apiKey, String model, int timeoutMs) {
        this.endpoint = trimTrailingSlash(baseUrl) + "/chat/completions";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 3000)))
                .build();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Summary summarize(String text) {
        if (text == null || text.isBlank()) {
            return Summary.empty(model);
        }
        long started = System.nanoTime();
        String content = call(text);
        int latency = (int) ((System.nanoTime() - started) / 1_000_000);
        return parse(content, latency);
    }

    @Override
    public String describe() {
        return "summary:http:" + model;
    }

    private String call(String text) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", 0.2);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", SummarizePrompt.system()),
                    Map.of("role", "user", "content", SummarizePrompt.user(abbreviate(text, MAX_INPUT_CHARS)))));
            body.put("stream", false);

            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (!apiKey.isEmpty()) {
                req.header("authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new ExtractException("摘要 HTTP " + resp.statusCode() + ": " + abbreviate(resp.body(), 200));
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
                throw new ExtractException("摘要响应里没有 choices[0].message.content");
            }
            return content.asText();
        } catch (ExtractException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractException("摘要被中断", e);
        } catch (Exception e) {
            throw new ExtractException("摘要调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析取向：能认出 JSON 就用 JSON，认不出就把整段当摘要。
     *
     * <p>唯一的硬失败是「什么都没给」——包括 {@code {"summary":""}} 这种形状正确但内容为空的情形。
     * 那种情况必须抛，否则库里会多出一条正文是空字符串的记忆，占着索引又什么都召回不了。
     */
    Summary parse(String content, int latencyMs) {
        String raw = content == null ? "" : content.trim();
        if (raw.isEmpty()) {
            throw new ExtractException("摘要响应为空");
        }
        boolean isJson = false;
        try {
            JsonNode root = mapper.readTree(HttpExtractor.extractJsonObject(raw));
            isJson = true;
            String text = text(root, "summary");
            if (!text.isEmpty()) {
                return new Summary(abbreviate(text, MAX_SUMMARY_CHARS),
                        keywords(root.path("keywords")), model, latencyMs);
            }
        } catch (ExtractException | java.io.IOException ignored) {
            // 没吐 JSON：模型给的东西本身可能就是能读的一句话，比丢掉划算。
            // 注意这里不能顺手吞掉「JSON 里 summary 为空」——那要按失败处理，见下。
        }
        if (isJson) {
            // 形状对、内容空：这是模型的明确回答「没什么可写」，不能拿整段 JSON 当摘要。
            throw new ExtractException("模型给了空摘要");
        }
        String plain = stripFence(raw);
        if (plain.isEmpty()) {
            throw new ExtractException("摘要响应为空");
        }
        return new Summary(abbreviate(plain, MAX_SUMMARY_CHARS), List.of(), model, latencyMs);
    }

    private List<String> keywords(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode n : node) {
            String v = n.asText("").trim();
            if (!v.isEmpty()) {
                seen.add(abbreviate(v, 80));
            }
            if (seen.size() >= 8) {
                break;
            }
        }
        return new ArrayList<>(seen);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText().trim() : "";
    }

    /** 去掉 ```json 围栏与首尾的引号：模型最常见的两种「多说一句」。 */
    static String stripFence(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
        }
        return s.trim();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String trimTrailingSlash(String s) {
        String t = s == null ? "" : s.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
