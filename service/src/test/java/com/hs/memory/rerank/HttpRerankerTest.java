package com.hs.memory.rerank;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRerankerTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String start(String responseBody, int status, String[] capturedRequest) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/rerank", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (capturedRequest != null) {
                capturedRequest[0] = body;
            }
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Test
    void parsesRelevanceScoreByIndex() throws Exception {
        String base = start("""
                {"results":[{"index":1,"relevance_score":0.91},{"index":0,"relevance_score":0.12}]}
                """, 200, null);
        Reranker r = new HttpReranker(base, "", "m", 2000, 12);
        double[] scores = r.score("q", List.of("a", "b"));
        assertArrayEquals(new double[]{0.12, 0.91}, scores, 1e-9);
    }

    @Test
    void acceptsTeiStyleScoreFieldWithoutIndex() throws Exception {
        String base = start("""
                {"results":[{"score":0.7},{"score":0.2}]}
                """, 200, null);
        Reranker r = new HttpReranker(base, "", "m", 2000, 12);
        assertArrayEquals(new double[]{0.7, 0.2}, r.score("q", List.of("a", "b")), 1e-9);
    }

    @Test
    void missingEntriesBecomeZeroNotHighScores() throws Exception {
        String base = start("""
                {"results":[{"index":0,"relevance_score":0.5}]}
                """, 200, null);
        Reranker r = new HttpReranker(base, "", "m", 2000, 12);
        assertArrayEquals(new double[]{0.5, 0.0}, r.score("q", List.of("a", "b")), 1e-9);
    }

    @Test
    void sendsModelQueryAndDocuments() throws Exception {
        String[] captured = new String[1];
        String base = start("""
                {"results":[{"index":0,"relevance_score":0.5}]}
                """, 200, captured);
        Reranker r = new HttpReranker(base, "sk-test", "my-model", 2000, 12);
        r.score("部署端口", List.of("端口是 8090"));
        String body = captured[0];
        assertTrue(body.contains("\"model\":\"my-model\""));
        assertTrue(body.contains("\"query\":\"部署端口\""));
        assertTrue(body.contains("端口是 8090"));
    }

    @Test
    void httpErrorBecomesRerankException() throws Exception {
        String base = start("{\"error\":\"boom\"}", 500, null);
        Reranker r = new HttpReranker(base, "", "m", 2000, 12);
        RerankException e = assertThrows(RerankException.class, () -> r.score("q", List.of("a")));
        assertTrue(e.getMessage().contains("500"));
    }

    @Test
    void emptyResultsIsAnError() throws Exception {
        String base = start("{\"results\":[]}", 200, null);
        Reranker r = new HttpReranker(base, "", "m", 2000, 12);
        assertThrows(RerankException.class, () -> r.score("q", List.of("a")));
    }

    @Test
    void noopIsNotAvailable() {
        Reranker r = new NoopReranker();
        assertFalse(r.available());
        assertThrows(RerankException.class, () -> r.score("q", List.of("a")));
    }

    @Test
    void unreachableEndpointFailsFast() {
        Reranker r = new HttpReranker("http://127.0.0.1:9/v1", "", "m", 300, 12);
        assertThrows(RerankException.class, () -> r.score("q", List.of("a")));
        assertEquals("http", r.describe().substring(0, 4));
    }
}
