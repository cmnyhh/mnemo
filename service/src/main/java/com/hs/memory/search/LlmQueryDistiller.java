package com.hs.memory.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 弃权之后，让模型把问题改写成检索词——比 {@link QuerySqueeze} 的规则替换更泛化。
 *
 * <p>为什么规则不够：规则表只能删掉「已经知道有害」的那几个词，它假定了用户问法落在
 * 我们枚举过的形状里。而真正难的一步是<b>词汇不对齐</b>——用户说「备份」，库里写的是
 * 「pg_dump 导出」；规则一个词都删不掉，一个词也补不上。
 *
 * <p>所以这里把<b>落选的候选记忆</b>一起喂给模型（伪相关反馈）：让它看着候选原文挑词，
 * 而不是凭空猜。这一步是区别所在——只给问题，模型只能用问题自己的词重写一遍，
 * 那和规则去噪没有本质差别；给了候选，它才可能把「备份」对上「pg_dump」。
 *
 * <p>失败一律返回 {@code null}。检索路径不能因为一个可选优化而报错或变慢到不可用：
 * 调用方拿到 null 就当这条路没走，退回原结果。
 */
public final class LlmQueryDistiller {

    /** 送给模型的候选条数与单条长度：只要够判断词汇，不要整段灌进去。 */
    private static final int MAX_CANDIDATES = 3;
    private static final int MAX_CANDIDATE_CHARS = 160;
    /** 提取出的检索词上限：超过这个长度就不再是「词」，而是另一段问题了。 */
    private static final int MAX_QUERY_CHARS = 60;
    private static final int MAX_QUESTION_CHARS = 600;

    private static final String SYSTEM = """
            你是检索系统的查询改写器。只输出一个 JSON 对象，不要解释、不要 markdown。""";

    private static final String TEMPLATE = """
            用户问题：
            %s

            当前检索召回的候选记忆（精排分低于阈值，可能相关也可能不相关）：
            %s

            第一步：判断候选里有没有能回答这个问题的信息。
            第二步：写出一行检索词，用来在记忆库里找能回答这个问题的条目。

            检索词的规则：
            - 只保留有区分度的名词性成分，去掉疑问词、语气词、请求套话（「我想知道」「帮我看看」）。
            - 不超过 30 个字，一行，不要分点，不要引号。
            - 如果问题本身没有可检索的实体（例如「那这个呢」），query 给空字符串。

            关于候选的两条硬约束（违反会把检索带偏）：
            - 只有确认候选**确实回答了**这个问题时，才可以用候选里的说法（术语、文件名、命令）；
            - 否则一律不许使用候选里的**具体值**——密码、令牌、编号、字符串常量、人名。
              这类值一旦被抄进检索词，就等于把「差不多的那条」当成答案，而真正的条目一条都召不回来。

            输出格式：{"relevant": true 或 false, "query": "..."}
            """;

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public LlmQueryDistiller(String baseUrl, String apiKey, String model, int timeoutMs) {
        this.endpoint = trimTrailingSlash(baseUrl) + "/chat/completions";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 3000)))
                .build();
    }

    /**
     * @param question   用户原话
     * @param candidates 本轮落选的候选（按分数从高到低），可为空
     * @return 可检索的短语；模型判定无可检索内容、或调用失败时返回 {@code null}
     */
    public String distill(String question, List<String> candidates) {
        if (question == null || question.isBlank()) {
            return null;
        }
        try {
            String prompt = TEMPLATE.formatted(clip(question, MAX_QUESTION_CHARS), render(candidates));
            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "temperature", 0,
                    "max_tokens", 120,
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM),
                            Map.of("role", "user", "content", prompt))));
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!apiKey.isEmpty()) {
                b.header("authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = http.send(b.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return null;
            }
            return parse(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // 检索路径上的可选优化：失败就当没有，不要抛出。
            return null;
        }
    }

    /** 模型是否认为候选里有能回答问题的信息——日志与排查用。 */
    private boolean lastRelevant = true;

    private String parse(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        String text = choices.get(0).path("message").path("content").asText("");
        String query = extractQuery(text);
        if (query == null) {
            return null;
        }
        query = query.replaceAll("\\s+", " ").trim();
        if (query.isEmpty()) {
            return null;
        }
        return query.length() > MAX_QUERY_CHARS ? query.substring(0, MAX_QUERY_CHARS) : query;
    }

    /** 模型偶尔会包 ```json、或干脆只给一行短语，两种都收。 */
    private String extractQuery(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode node = mapper.readTree(text.substring(start, end + 1));
                lastRelevant = node.path("relevant").asBoolean(true);
                String q = node.path("query").asText("");
                return q.isBlank() ? null : q;
            } catch (Exception ignored) {
                // 落到下面按纯文本处理
            }
        }
        String line = text.replace("```json", "").replace("```", "").trim();
        return line.isBlank() ? null : line;
    }

    private static String render(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "（无：本次没有召回任何候选）";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String c : candidates) {
            if (c == null || c.isBlank()) {
                continue;
            }
            if (++i > MAX_CANDIDATES) {
                break;
            }
            sb.append(i).append(". ").append(clip(c, MAX_CANDIDATE_CHARS)).append('\n');
        }
        return sb.isEmpty() ? "（无：本次没有召回任何候选）" : sb.toString();
    }

    private static String clip(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String trimTrailingSlash(String url) {
        String u = url == null ? "" : url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /** 上一次 {@link #distill} 是否判定候选相关内容（供调用方决定要不要标注低置信）。 */
    public boolean lastRelevant() {
        return lastRelevant;
    }

    public String describe() {
        return "distill:http:" + model;
    }
}
