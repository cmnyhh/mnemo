package com.hs.memory.extract;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 摘要器的解析取向与抽取器相反：宽松。模型没吐 JSON、多加了围栏、说了句开场白，
 * 都要尽量把内容捞出来——摘要是记录的最后一道，丢掉就是这一轮彻底不见了。
 */
class HttpSummarizerTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startWithContent(String content, int status, String[] captured) throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                + quote(content) + "}}]}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String req = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (captured != null) {
                captured[0] = req;
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

    private HttpSummarizer summarizer(String base) {
        return new HttpSummarizer(base, "", "m", 2000);
    }

    @Test
    @DisplayName("标准 JSON：正文 + keywords 都要，keywords 进召回提示")
    void parsesSummaryAndKeywords() throws Exception {
        String base = startWithContent("""
                {"summary":"记忆服务 API 端口是 8080，宿主只绑 127.0.0.1。","keywords":["记忆服务","8080","127.0.0.1"]}
                """, 200, null);

        Summary s = summarizer(base).summarize("用户：端口多少\\n助手：8080");

        assertTrue(s.usable());
        assertEquals("记忆服务 API 端口是 8080，宿主只绑 127.0.0.1。", s.text());
        assertEquals(List.of("记忆服务", "8080", "127.0.0.1"), s.keywords());
        assertEquals("m", s.model());
    }

    @Test
    @DisplayName("模型直接给了一段话（不是 JSON）⇒ 整段当摘要，不丢")
    void acceptsPlainText() throws Exception {
        String base = startWithContent("这一轮在确认部署结果，没有新信息。", 200, null);

        Summary s = summarizer(base).summarize("x");

        assertEquals("这一轮在确认部署结果，没有新信息。", s.text());
        assertTrue(s.keywords().isEmpty());
    }

    @Test
    @DisplayName("``` 围栏包裹的纯文本：去掉围栏再收")
    void stripsFenceOnPlainText() throws Exception {
        String base = startWithContent("```\n这一轮在讨论端口配置。\n```", 200, null);

        assertEquals("这一轮在讨论端口配置。", summarizer(base).summarize("x").text());
    }

    @Test
    @DisplayName("形状对但内容空：必须抛，否则库里会多一条空正文的记忆")
    void emptySummaryIsAnError() throws Exception {
        String base = startWithContent("{\"summary\":\"\",\"keywords\":[]}", 200, null);

        assertThrows(ExtractException.class, () -> summarizer(base).summarize("x"));
    }

    @Test
    @DisplayName("摘要过长截到上限：再长就是把原文换个说法搬了一遍")
    void clipsOverlongSummary() throws Exception {
        String longText = "记".repeat(HttpSummarizer.MAX_SUMMARY_CHARS + 100);
        String base = startWithContent("{\"summary\":\"" + longText + "\"}", 200, null);

        Summary s = summarizer(base).summarize("x");

        assertEquals(HttpSummarizer.MAX_SUMMARY_CHARS + 1, s.text().length());
        assertTrue(s.text().endsWith("…"));
    }

    @Test
    @DisplayName("提示词里必须有「保留硬信息」这条，否则摘要会退化成泛泛而谈")
    void sendsSummaryRules() throws Exception {
        String[] captured = new String[1];
        String base = startWithContent("{\"summary\":\"ok\"}", 200, captured);

        summarizer(base).summarize("用户：端口多少");

        String body = captured[0];
        assertTrue(body.contains("\"temperature\":0.2"));
        assertTrue(body.contains("硬信息"));
        assertTrue(body.contains("keywords"));
        assertTrue(body.contains("用户：端口多少"));
    }

    @Test
    @DisplayName("HTTP 500 变成异常，由上层回落原文")
    void httpErrorBecomesException() throws Exception {
        String base = startWithContent("boom", 500, null);

        ExtractException e = assertThrows(ExtractException.class, () -> summarizer(base).summarize("x"));
        assertTrue(e.getMessage().contains("500"));
    }

    @Test
    @DisplayName("空白输入不调模型，直接返回空摘要")
    void blankInputSkipsCall() {
        Summary s = new HttpSummarizer("http://127.0.0.1:1/v1", "", "m", 1000).summarize("  ");
        assertFalse(s.usable());
    }

    @Test
    @DisplayName("Noop：不可用，调用即抛，上层回落原文")
    void noopIsNotAvailable() {
        Summarizer s = new NoopSummarizer();
        assertFalse(s.available());
        assertThrows(ExtractException.class, () -> s.summarize("x"));
        assertEquals("none", s.describe());
    }
}
