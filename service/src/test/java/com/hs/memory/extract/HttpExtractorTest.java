package com.hs.memory.extract;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpExtractorTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 把抽取结果包成 chat/completions 的响应体；content 内部会再被 JSON 转义。 */
    private String startWithContent(String content, int status, String[] capturedRequest) throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                + quote(content) + "}}]}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String req = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (capturedRequest != null) {
                capturedRequest[0] = req;
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private HttpExtractor extractor(String base) {
        return new HttpExtractor(base, "", "m", 2000, 8, 0.35);
    }

    @Test
    void parsesItemsAndKeepsHints() throws Exception {
        String base = startWithContent("""
                {"items":[{"kind":"fact","narrative":"知识库 API 服务监听 10.20.0.9:8080","subject":"知识库API",
                "predicate":"监听端口","object":"8080","facts":{"port":"8080"},
                "entities":["10.20.0.9:8080"],"recall_hints":["接口地址怎么填","端口是多少"],"confidence":0.9}]}
                """, 200, null);

        ExtractResult r = extractor(base).extract("对话内容");

        assertEquals(1, r.count());
        ExtractedItem item = r.items().get(0);
        assertEquals("fact", item.kind());
        assertEquals(8080, 8080);
        assertEquals("8080", item.objectValue());
        assertEquals(List.of("接口地址怎么填", "端口是多少"), item.recallHints());
        assertTrue(item.retrievalText().contains("接口地址怎么填"));
        assertTrue(item.hasTriple());
    }

    /**
     * 线上真实故障：GLM-4-9B 想说「没什么可记的」，吐的是 {@code {"items":[]"}}（多一个引号）。
     * 严格解析失败 → 上层回落按段硬切 → 整段提示词被写成 episode。
     * 这条用例锁死「空结论不能被当成格式错误」。
     */
    @Test
    void malformedEmptyItemsIsAnEmptyConclusionNotAFailure() throws Exception {
        String base = startWithContent("{\"items\":[]\"}", 200, null);

        ExtractResult r = extractor(base).extract("对话内容");

        assertEquals(0, r.count());
        assertTrue(r.admitted());
    }

    /**
     * 线上真实输出（2026-10-22 13:24）：模型判了「不该记」，但收尾多吐一个引号。
     * 不修这一处，每次「不该记」都会退化成硬切，把整轮提示词写成记忆。
     */
    @Test
    void repairsStrayTrailingQuoteOnAdmitFalse() throws Exception {
        String base = startWithContent(
                "{\"admit\":false,\"reason\":\"运行态脚手架内容\",\"items\":[]\"}", 200, null);

        ExtractResult r = extractor(base).extract("对话内容");

        assertEquals(0, r.count());
        assertFalse(r.admitted());
        assertEquals("运行态脚手架内容", r.reason());
    }

    @Test
    void admitFalseYieldsNoItems() throws Exception {        String base = startWithContent("""
                {"admit":false,"reason":"提示词模板","items":[]}
                """, 200, null);

        ExtractResult r = extractor(base).extract("对话内容");

        assertEquals(0, r.count());
        assertFalse(r.admitted());
        assertEquals("提示词模板", r.reason());
    }

    /**
     * 准入被拒只否掉「沉淀成事实」，不否掉「这轮聊过什么」。
     * 摘要必须跟着结果一起出来，否则被拒的轮次在记忆里会彻底消失。
     */
    @Test
    void admitFalseStillCarriesSummary() throws Exception {
        String base = startWithContent("""
                {"admit":false,"reason":"闲聊","summary":"用户随口问了句今天怎么样，没有实质内容。","items":[]}
                """, 200, null);

        ExtractResult r = extractor(base).extract("对话内容");

        assertFalse(r.admitted());
        assertEquals("用户随口问了句今天怎么样，没有实质内容。", r.summary());
    }

    @Test
    void admittedResultCarriesSummaryToo() throws Exception {
        String base = startWithContent("""
                {"admit":true,"summary":"确认了记忆服务端口是 8080。","items":[]}
                """, 200, null);

        assertEquals("确认了记忆服务端口是 8080。", extractor(base).extract("x").summary());
    }

    @Test
    void stripsMarkdownFence() throws Exception {
        String base = startWithContent("""
                ```json
                {"items":[{"kind":"preference","narrative":"对外文档一律不写云端/本地之分","confidence":0.95}]}
                ```
                """, 200, null);

        ExtractResult r = extractor(base).extract("x");
        assertEquals(1, r.count());
        assertEquals("preference", r.items().get(0).kind());
        assertNull(r.items().get(0).subjectKey());
    }

    @Test
    void factWithoutTripleIsDemotedToEpisode() throws Exception {
        String base = startWithContent("""
                {"items":[{"kind":"fact","narrative":"某服务用了 8080","confidence":0.8}]}
                """, 200, null);

        ExtractedItem item = extractor(base).extract("x").items().get(0);
        assertEquals("episode", item.kind());
        assertFalse(item.hasTriple());
    }

    @Test
    void lowConfidenceIsDropped() throws Exception {
        String base = startWithContent("""
                {"items":[{"kind":"episode","narrative":"可能记一下","confidence":0.1},
                          {"kind":"episode","narrative":"确定的结论","confidence":0.8}]}
                """, 200, null);

        ExtractResult r = extractor(base).extract("x");
        assertEquals(1, r.count());
        assertEquals("确定的结论", r.items().get(0).narrative());
    }

    @Test
    void takesFirstBalancedObjectWhenModelAppendsExtraOutput() throws Exception {
        String base = startWithContent("""
                {"items":[{"kind":"episode","narrative":"结论","confidence":0.8}]}
                另外补充一句说明。
                {"note":"多余输出"}
                """, 200, null);
        ExtractResult r = extractor(base).extract("x");
        assertEquals(1, r.count());
        assertEquals("结论", r.items().get(0).narrative());
    }

    @Test
    void emptyItemsIsValidNotAnError() throws Exception {
        String base = startWithContent("{\"items\":[]}", 200, null);
        assertEquals(0, extractor(base).extract("闲聊几句").count());
    }

    @Test
    void missingItemsKeyIsAnError() throws Exception {
        String base = startWithContent("{\"result\":\"ok\"}", 200, null);
        assertThrows(ExtractException.class, () -> extractor(base).extract("x"));
    }

    @Test
    void illegalJsonIsAnError() throws Exception {
        String base = startWithContent("我觉得这段没什么好记的", 200, null);
        assertThrows(ExtractException.class, () -> extractor(base).extract("x"));
    }

    @Test
    void httpErrorBecomesExtractException() throws Exception {
        String base = startWithContent("boom", 500, null);
        ExtractException e = assertThrows(ExtractException.class, () -> extractor(base).extract("x"));
        assertTrue(e.getMessage().contains("500"));
    }

    @Test
    void sendsSystemPromptWithNarrativeRules() throws Exception {
        String[] captured = new String[1];
        String base = startWithContent("{\"items\":[]}", 200, captured);
        extractor(base).extract("我们决定用 PG18");

        String body = captured[0];
        assertTrue(body.contains("\"model\":\"m\""));
        assertTrue(body.contains("\"temperature\":0.1"));
        assertTrue(body.contains("自包含"));
        assertTrue(body.contains("recall_hints"));
        assertTrue(body.contains("我们决定用 PG18"));
    }

    @Test
    void noopIsNotAvailable() {
        Extractor e = new NoopExtractor();
        assertFalse(e.available());
        assertThrows(ExtractException.class, () -> e.extract("x"));
        assertEquals("none", e.describe());
    }
}
