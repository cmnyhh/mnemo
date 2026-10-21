package com.hs.memory.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TurnSegmenterTest {

    @Test
    void keepsAShortReplyWhole() {
        List<String> segments = IngestService.splitSegments("部署在 example.com，用 docker compose 跑三个容器。");
        assertThat(segments).containsExactly("部署在 example.com，用 docker compose 跑三个容器。");
    }

    @Test
    void splitsOnBlankLinesAndKeepsEachParagraphIntact() {
        String text = "第一段：记忆库选 PostgreSQL 18。\n\n第二段：向量库用 Qdrant，4096 维。\n\n第三段：缓存不上 Redis。";
        assertThat(IngestService.splitSegments(text)).containsExactly(
                "第一段：记忆库选 PostgreSQL 18。",
                "第二段：向量库用 Qdrant，4096 维。",
                "第三段：缓存不上 Redis。");
    }

    @Test
    void wrapsALongParagraphAtSentenceBoundaries() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 1500) {
            sb.append("这一段在讲同一件事，长度会超过单个段落的上限。");
        }
        List<String> segments = IngestService.splitSegments(sb.toString());
        assertThat(segments).hasSizeGreaterThan(1);
        assertThat(segments).allSatisfy(segment -> {
            // 允许一个句号越界，但不能整段超出一截
            assertThat(segment.length()).isLessThanOrEqualTo(601);
            assertThat(segment).endsWith("。");
        });
        assertThat(String.join("", segments)).isEqualTo(sb.toString());
    }

    @Test
    void joinsConsecutiveShortLinesThatFitTogether() {
        String text = "端口改成 9090\n负责人是张三";
        assertThat(IngestService.splitSegments(text)).containsExactly("端口改成 9090\n负责人是张三");
    }

    @Test
    void recognisesQuestionsOnlyByTheirPunctuation() {
        assertThat(IngestService.isQuestion("记忆服务部署在哪台机器上？")).isTrue();
        assertThat(IngestService.isQuestion("what is the port?")).isTrue();
        assertThat(IngestService.isQuestion("端口改成 9090。")).isFalse();
        assertThat(IngestService.isQuestion("端口是 8080 吗")).isFalse();
    }
}
