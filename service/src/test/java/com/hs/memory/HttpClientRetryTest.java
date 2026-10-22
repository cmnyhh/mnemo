package com.hs.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hs.memory.embed.HttpEmbeddingClient;
import com.hs.memory.rerank.HttpReranker;
import com.hs.memory.rerank.RerankException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 公网推理端点的重试行为。
 *
 * <p>这些用例守的是一条线上事故：`rerank.timeout-ms` 只有 800ms、且没有重试，
 * 端点一次抖动就静默退回 RRF —— 接口照常 200，只是结果变差，任何时候都看不出来。
 */
class HttpClientRetryTest {

    private static final String RERANK_OK =
            "{\"results\":[{\"index\":0,\"relevance_score\":0.91},{\"index\":1,\"relevance_score\":0.23}]}";
    private static final String EMBED_OK =
            "{\"data\":[{\"embedding\":[0.1,0.2,0.3,0.4]}]}";

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** 起一个假端点：按顺序返回给定状态码，最后一个状态码会一直重复。 */
    private String start(int... statuses) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange ex) -> {
            int i = hits.getAndIncrement();
            int status = statuses[Math.min(i, statuses.length - 1)];
            String resp;
            if (ex.getRequestURI().getPath().endsWith("/embeddings")) {
                resp = status == 200 ? EMBED_OK : "{\"error\":\"boom\"}";
            } else {
                resp = status == 200 ? RERANK_OK : "{\"error\":\"boom\"}";
            }
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void rerankRetriesOnceWhenTheEndpointIsRateLimited() throws Exception {
        String base = start(429, 200);
        HttpReranker reranker = new HttpReranker(base, "k", "m", 2000, 8);

        double[] scores = reranker.score("q", List.of("doc a", "doc b"));

        assertThat(hits.get()).isEqualTo(2);
        assertThat(scores[0]).isGreaterThan(scores[1]);
    }

    @Test
    void rerankRetriesOnceOnServerError() throws Exception {
        String base = start(503, 200);
        HttpReranker reranker = new HttpReranker(base, "k", "m", 2000, 8);

        reranker.score("q", List.of("doc a", "doc b"));

        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void rerankDoesNotRetryWhenTheRequestItselfIsWrong() throws Exception {
        // 400 = key/模型名/参数错，重试一次只会多花一整个超时预算。
        String base = start(400);
        HttpReranker reranker = new HttpReranker(base, "k", "m", 2000, 8);

        assertThatThrownBy(() -> reranker.score("q", List.of("a", "b")))
                .isInstanceOf(RerankException.class)
                .satisfies(e -> assertThat(((RerankException) e).retryable()).isFalse());
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void rerankGivesUpAfterTwoAttempts() throws Exception {
        String base = start(500);
        HttpReranker reranker = new HttpReranker(base, "k", "m", 2000, 8);

        assertThatThrownBy(() -> reranker.score("q", List.of("a", "b")))
                .isInstanceOf(RerankException.class);
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void embeddingRetriesOnceOnServerError() throws Exception {
        String base = start(503, 200);
        HttpEmbeddingClient client = new HttpEmbeddingClient(base, "k", "m", 4, 2000);

        float[] vec = client.embed("hello");

        assertThat(hits.get()).isEqualTo(2);
        assertThat(vec).hasSize(4);
    }

    @Test
    void embeddingDoesNotRetryOnDimensionMismatch() throws Exception {
        // 维度不符是配置错，重试没有意义；必须立刻失败，否则会白白多打一次端点。
        String base = start(200);
        HttpEmbeddingClient client = new HttpEmbeddingClient(base, "k", "m", 8, 2000);

        assertThatThrownBy(() -> client.embed("hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("维度不符");
        assertThat(hits.get()).isEqualTo(1);
    }
}
