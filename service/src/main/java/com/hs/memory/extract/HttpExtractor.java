package com.hs.memory.extract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容的 /chat/completions 抽取器（Xinference、vLLM、Ollama /v1、各类云厂商都吃这套）。
 *
 * <p>解析取向是严格的：模型输出不是合法 JSON、或必填字段缺失，一律抛异常，由上层整批丢弃。
 * 「猜一个差不多的记忆写进去」比「这一轮没记住」危险得多——错误的记忆会被反复注入。
 */
public class HttpExtractor implements Extractor {

    private static final Set<String> KINDS = Set.of("preference", "fact", "episode");

    /**
     * 「空结论」的形状：{@code {"items":[]}}，容忍模型在末尾多吐一个引号。
     * 只认空数组，不认别的东西——这条捷径的边界必须是「意图明确到不用猜」。
     */
    private static final java.util.regex.Pattern EMPTY_ITEMS =
            java.util.regex.Pattern.compile("\"items\"\\s*:\\s*\\[\\s*\\]");

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final int maxItems;
    private final double minConfidence;

    public HttpExtractor(String baseUrl, String apiKey, String model, int timeoutMs,
                         int maxItems, double minConfidence) {
        this.endpoint = trimTrailingSlash(baseUrl) + "/chat/completions";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
        this.maxItems = Math.max(1, maxItems);
        this.minConfidence = clamp01(minConfidence);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 3000)))
                .build();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public ExtractResult extract(String text) {
        if (text == null || text.isBlank()) {
            return ExtractResult.empty(model);
        }
        long started = System.nanoTime();
        String content = call(text);
        Parsed parsed = parse(content);
        int latency = (int) ((System.nanoTime() - started) / 1_000_000);
        if (!parsed.admitted()) {
            // 准入被拒：这段内容不该进长期记忆（提示词模板/工具回显/闲聊…）。
            // 与「抽到 0 条」分开返回，否则排障时分不清是抽取太保守还是上游发错了东西。
            // 摘要照样带出来：被拒的是「抽取」，不是「记录」。
            return ExtractResult.rejected(model, latency, parsed.reason(), parsed.summary());
        }
        return new ExtractResult(parsed.items(), model, latency, false, true, parsed.reason(),
                parsed.summary());
    }

    /** 一次模型调用同时给三件事：准入门结论、抽到的条目、这一轮聊了什么的记录。 */
    record Parsed(List<ExtractedItem> items, boolean admitted, String reason, String summary) {
    }

    @Override
    public String describe() {
        return "http:" + model;
    }

    private String call(String text) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", 0.1);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", ExtractPrompt.system(maxItems)),
                    Map.of("role", "user", "content", ExtractPrompt.user(abbreviate(text, 12000)))));
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
                throw new ExtractException("抽取 HTTP " + resp.statusCode() + ": " + abbreviate(resp.body(), 200));
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
                throw new ExtractException("抽取响应里没有 choices[0].message.content");
            }
            return content.asText();
        } catch (ExtractException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractException("抽取被中断", e);
        } catch (Exception e) {
            throw new ExtractException("抽取调用失败: " + e.getMessage(), e);
        }
    }

    Parsed parse(String content) {
        JsonNode root = null;
        ExtractException failure = null;
        try {
            root = mapper.readTree(extractJsonObject(content));
        } catch (ExtractException | JsonProcessingException e) {
            failure = e instanceof ExtractException ee ? ee
                    : new ExtractException("抽取输出不是合法 JSON: " + abbreviate(content, 160), e);
            // GLM-4-9B 有个固定毛病：在收尾处多吐一个引号（`..."items":[]"}`），
            // 括号配对因此永远不闭合。这类畸形只差一个字符，值得修一次再判失败——
            // 因为**失败会把整轮提示词写进记忆**（回落硬切），代价远大于一次重试解析。
            String repaired = repairJson(content);
            if (repaired != null) {
                try {
                    root = mapper.readTree(repaired);
                    failure = null;
                } catch (Exception ignored) {
                    // 修不好就还是失败，下面的空结论兜底与抛出逻辑照旧
                }
            }
        }
        if (root == null) {
            // 最后一道：内容里明确写了 items 是空数组，就当「这轮没什么可记的」。
            // 空结论被当成格式错误，正是线上杂音的主要来源。
            if (EMPTY_ITEMS.matcher(content).find()) {
                return new Parsed(List.of(), true, "模型判定这轮没有值得记的内容", null);
            }
            throw failure;
        }
        String reason = text(root, "reason");
        // 缺省放行：提示词是随版本走的，旧模型/旧缓存里没有 admit 字段时
        // 不能因为「没写」就整条链路突然什么都不记。
        boolean admitted = !root.has("admit") || root.path("admit").asBoolean(true);
        // 摘要与准入无关：admit=false 的轮次也要记「聊了什么」。
        // 上限跟摘要器保持一致，否则同一份提示词走两条路会出现两种长度。
        String summary = abbreviate(text(root, "summary"), HttpSummarizer.MAX_SUMMARY_CHARS);
        if (!admitted) {
            return new Parsed(List.of(), false,
                    reason.isEmpty() ? "模型判定不值得进长期记忆" : abbreviate(reason, 120),
                    summary.isEmpty() ? null : summary);
        }
        JsonNode arr = root.path("items");
        if (!arr.isArray()) {
            throw new ExtractException("抽取输出的 items 不是数组: " + abbreviate(content, 160));
        }

        List<ExtractedItem> out = new ArrayList<>();
        for (JsonNode node : arr) {
            if (out.size() >= maxItems) {
                break;
            }
            ExtractedItem item = toItem(node);
            if (item != null) {
                out.add(item);
            }
        }
        return new Parsed(List.copyOf(out), true, reason.isEmpty() ? null : abbreviate(reason, 120),
                summary.isEmpty() ? null : summary);
    }

    private ExtractedItem toItem(JsonNode node) {
        String kind = text(node, "kind").toLowerCase();
        if (!KINDS.contains(kind)) {
            return null;
        }
        String narrative = text(node, "narrative");
        if (narrative.isEmpty()) {
            return null;
        }
        double confidence = node.path("confidence").asDouble(0.6);
        if (clamp01(confidence) < minConfidence) {
            return null;
        }
        String subject = text(node, "subject");
        String predicate = text(node, "predicate");
        String object = text(node, "object");
        if ("fact".equals(kind) && (subject.isEmpty() || predicate.isEmpty() || object.isEmpty())) {
            // fact 没有三件套就无法参与冲突消解，也无法被结构化召回，降级成 episode 更诚实
            kind = "episode";
        }
        return new ExtractedItem(
                kind,
                abbreviate(narrative, 2000),
                subject.isEmpty() ? null : subject,
                predicate.isEmpty() ? null : predicate,
                object.isEmpty() ? null : object,
                stringMap(node.path("facts")),
                stringList(node.path("entities"), 12),
                stringList(node.path("recall_hints"), 8),
                clamp01(confidence));
    }

    private Map<String, String> stringMap(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            String v = e.getValue().asText("");
            if (!v.isBlank()) {
                out.put(e.getKey(), abbreviate(v, 500));
            }
        });
        return Map.copyOf(out);
    }

    private List<String> stringList(JsonNode node, int limit) {
        if (!node.isArray()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode n : node) {
            String v = n.asText("").trim();
            if (!v.isEmpty()) {
                seen.add(abbreviate(v, 200));
            }
            if (seen.size() >= limit) {
                break;
            }
        }
        return List.copyOf(seen);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText().trim() : (v.isNumber() ? v.asText() : "");
    }

    /**
     * 针对「只差一个字符」的畸形做一次修复，修不好返回 {@code null}。
     *
     * <p>只处理两类**形状确定**的毛病，不做任何语义猜测：
     * <ol>
     *   <li>收尾多一个引号：{@code ..."items":[]"}} → {@code ..."items":[]}}；</li>
     *   <li>对象后面又跟了一个引号或说明文字：截到最后一个 {@code }} 为止。</li>
     * </ol>
     * 修完之后仍然要过一遍真正的 JSON 解析，解析不过就是真的坏，照旧抛。
     */
    static String repairJson(String content) {
        if (content == null) {
            return null;
        }
        String s = content.trim();
        s = s.replaceAll("\\]\"\\s*\\}", "]}").replaceAll("\\}\"\\s*\\}", "}}");
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return s.substring(start, end + 1);
    }

    /**
     * 模型常把 JSON 包在 ```json 里、在前面加一句「好的，以下是结果」，甚至吐出两个对象。
     * 所以按括号配对取**第一个完整对象**——只取形状，不改内容，不算「猜」。
     */
    static String extractJsonObject(String content) {
        String s = content == null ? "" : content.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
        }
        int start = s.indexOf('{');
        if (start < 0) {
            throw new ExtractException("抽取输出里找不到 JSON 对象: " + abbreviate(content, 160));
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
        throw new ExtractException("抽取输出的 JSON 没有闭合: " + abbreviate(content, 160));
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
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
