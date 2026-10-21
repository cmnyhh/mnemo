package com.hs.memory.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的看图客户端（{@code /chat/completions} + {@code image_url} data URL）。
 *
 * <p>它只做一件事：把一张图变成「一段可被检索的文字」。所以提示词的两段输出都必须是
 * 检索视角的——caption 用用户会怎么问来写，ocr 逐字保留，方便「图里那行报错是什么」这种提问。
 *
 * <p>送模型的图有上限（{@value #MAX_VISION_BYTES} 字节）：base64 之后体积再涨三分之一，
 * 20MB 的照片会让请求直接超时或 413，那时用户看到的是「上传成功但没有图注」——
 * 比整次上传失败好，但也不该悄悄发生，所以走 note 说明。
 */
public class HttpVisionClient implements VisionClient {

    /** 单张图送模型的原始字节上限。 */
    static final int MAX_VISION_BYTES = 6 * 1024 * 1024;

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public HttpVisionClient(String baseUrl, String apiKey, String model, int timeoutMs) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = base + "/chat/completions";
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
    public Caption describe(byte[] image, String mimeType) throws VisionException {
        if (image == null || image.length == 0) {
            throw new VisionException("图片为空");
        }
        if (image.length > MAX_VISION_BYTES) {
            throw new VisionException("图片 " + image.length + " 字节，超过看图上限 " + MAX_VISION_BYTES);
        }
        long started = System.nanoTime();
        String content = call(image, mimeType == null || mimeType.isBlank() ? "image/png" : mimeType);
        Caption parsed = parse(content);
        int latency = (int) ((System.nanoTime() - started) / 1_000_000);
        return new Caption(parsed.caption(), parsed.ocr(), model, latency);
    }

    private String call(byte[] image, String mime) throws VisionException {
        try {
            String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image);

            Map<String, Object> text = Map.of("type", "text", "text", PROMPT);
            Map<String, Object> imagePart = Map.of("type", "image_url",
                    "image_url", Map.of("url", dataUrl));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", 0.1);
            body.put("max_tokens", 600);
            body.put("messages", List.of(Map.of("role", "user", "content", List.of(text, imagePart))));
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
                throw new VisionException("看图 HTTP " + resp.statusCode() + ": " + abbreviate(resp.body(), 200));
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
                throw new VisionException("看图响应里没有 choices[0].message.content");
            }
            return content.asText();
        } catch (VisionException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VisionException("看图被中断", e);
        } catch (Exception e) {
            throw new VisionException("看图调用失败: " + e.getMessage(), e);
        }
    }

    /** 解析容忍「包了代码块」「多说了两句」，但两个字段都没有时算失败。 */
    Caption parse(String content) throws VisionException {
        String json = jsonObject(content);
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new VisionException("看图输出不是合法 JSON: " + abbreviate(content, 160), e);
        }
        String caption = text(root, "caption");
        String ocr = text(root, "ocr");
        if (caption.isEmpty() && ocr.isEmpty()) {
            throw new VisionException("看图输出里 caption 与 ocr 都是空的");
        }
        return new Caption(caption, ocr, model, 0);
    }

    static String jsonObject(String content) throws VisionException {
        String s = content == null ? "" : content.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(nl + 1);
            }
        }
        int start = s.indexOf('{');
        if (start < 0) {
            throw new VisionException("看图输出里找不到 JSON 对象: " + abbreviate(content, 160));
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        throw new VisionException("看图输出的 JSON 没有闭合: " + abbreviate(content, 160));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText().trim() : "";
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static final String PROMPT = """
            你在为一个长期记忆系统建立图片索引。这张图以后会被用户用自然语言检索，
            所以你的描述必须包含「用户会用来找它的那些词」。
            只输出一个 JSON 对象，不要解释、不要代码块：
            {"caption":"一句话，60 字以内，说清图是什么（主体 + 场景 + 关键信息）","ocr":"图中所有可辨认的文字，按阅读顺序换行分隔；没有文字则空字符串"}
            要求：
            1. caption 用陈述句，不要「这是一张图片」这类空话；
            2. 如果是报错截图，caption 要包含错误类型与关键提示语；
            3. 如果是架构图/流程图，caption 要说明画的是什么系统、有哪些主要模块；
            4. ocr 逐字保留，不要改写、不要翻译。
            """;
}
