package com.hs.memory.obs;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 观测层的两个「不能让调用方失望」的性质：数得准，以及分阶段排序能一眼指出最贵的那段。
 */
@SuppressWarnings("unchecked")
class MetricsRecorderTest {

    private final MetricsRecorder metrics = new MetricsRecorder(new SimpleMeterRegistry());

    @Test
    void searchLatencyAndOutcomeAreRecorded() {
        metrics.search("hybrid", "hit", 12_000_000L);

        Map<String, Object> snap = metrics.snapshot();
        Map<String, Object> search = (Map<String, Object>) snap.get("search");

        assertThat(search.get("count")).isEqualTo(1L);
        assertThat((Double) search.get("maxMs")).isGreaterThan(10.0);
        assertThat((Map<String, Long>) snap.get("outcomes")).containsEntry("hit", 1L);
    }

    @Test
    void stagesAreSortedByP95Descending() {
        metrics.searchStage("embed", 1_000_000L);
        metrics.searchStage("bm25", 2_000_000L);
        metrics.searchStage("rerank", 100_000_000L);

        List<Map<String, Object>> stages =
                (List<Map<String, Object>>) metrics.snapshot().get("stages");

        // 排序是为了让人不用自己比四行数字：最贵的排最前
        assertThat(stages).extracting(r -> r.get("stage"))
                .containsExactly("rerank", "bm25", "embed");
    }

    @Test
    void degradeAndReviewCountersSurviveRepeatedCalls() {
        metrics.ingest("llm-extract", "ok", 3_000_000L);
        metrics.degrade("vector");
        metrics.degrade("vector");
        metrics.review("downgrade");

        Map<String, Object> snap = metrics.snapshot();

        assertThat((Map<String, Long>) snap.get("degrade")).containsEntry("vector", 2L);
        assertThat((Map<String, Long>) snap.get("review")).containsEntry("downgrade", 1L);
        assertThat(((Map<String, Object>) snap.get("ingest")).get("count")).isEqualTo(1L);
    }

    @Test
    void emptyRegistryDoesNotExplode() {
        Map<String, Object> snap = new MetricsRecorder(new SimpleMeterRegistry()).snapshot();

        assertThat(((Map<String, Object>) snap.get("search")).get("p95Ms")).isEqualTo(0.0);
        assertThat((List<?>) snap.get("stages")).isEmpty();
    }
}
