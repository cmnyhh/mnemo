package com.hs.memory.tier;

import static org.assertj.core.api.Assertions.assertThat;

import com.hs.memory.search.Ranking;
import java.util.List;
import org.junit.jupiter.api.Test;

class TierPolicyTest {

    @Test
    void episodesInATurnJoinTheWorkingSet() {
        // 当前会话里说出的话是工作集；同一段话从导入进来就不是
        assertThat(TierPolicy.initialTier("episode", "turn", true)).isEqualTo(TierPolicy.T1);
        assertThat(TierPolicy.initialTier("episode", "import", true)).isEqualTo(TierPolicy.T2);
        assertThat(TierPolicy.initialTier("episode", "turn", false)).isEqualTo(TierPolicy.T2);
    }

    @Test
    void structuredFactsAreLongTermEvenInsideATurn() {
        // fact/preference 是对话的沉淀物，不该随会话结束就掉出默认检索层
        assertThat(TierPolicy.initialTier("fact", "turn", true)).isEqualTo(TierPolicy.T2);
        assertThat(TierPolicy.initialTier("preference", "turn", true)).isEqualTo(TierPolicy.T2);
    }

    @Test
    void boostOrdersTheLayers() {
        double t0 = TierPolicy.boost("T0");
        double t1 = TierPolicy.boost("T1");
        double t2 = TierPolicy.boost("T2");
        double t3 = TierPolicy.boost("T3");
        assertThat(t0).isGreaterThan(t1);
        assertThat(t1).isGreaterThan(t2);
        assertThat(t2).isGreaterThan(t3);
        assertThat(t2).isZero();
    }

    @Test
    void boostMovesTheFinalScoreButDoesNotChangeTrust() {
        double low = Ranking.finalScore(1.0, 1.0, 1.0, 1.0, 0, 0, "low", 0);
        double lowPinned = Ranking.finalScore(1.0, 1.0, 1.0, 1.0, 0, 0, "low", TierPolicy.boost("T0"));
        // 低可信记录被钉住后仍然进不了「满分」区间，但确实被抬高了
        assertThat(lowPinned).isGreaterThan(low);
        assertThat(lowPinned).isLessThan(1.0);
    }

    @Test
    void unknownTierFallsBackToLongTerm() {
        assertThat(TierPolicy.norm(null)).isEqualTo(TierPolicy.T2);
        assertThat(TierPolicy.norm(" t1 ")).isEqualTo(TierPolicy.T1);
        assertThat(TierPolicy.norm("bogus")).isEqualTo(TierPolicy.T2);
    }

    @Test
    void archiveIsNotSearchableByDefault() {
        assertThat(TierPolicy.defaultSearchable("T2")).isTrue();
        assertThat(TierPolicy.defaultSearchable("T0")).isTrue();
        assertThat(TierPolicy.defaultSearchable("T3")).isFalse();
    }

    @Test
    void pinnedBlockOmitsScoresAndStopsAtBudget() {
        List<Ranking.InjectionEntry> entries = List.of(
                new Ranking.InjectionEntry("preference", "T0", 0.9, "回答一律用中文"),
                new Ranking.InjectionEntry("fact", "T0", 0.8, "生产库在 db-primary，端口 5432"));
        String block = Ranking.renderPinned(entries, 1000);
        // 0.8.0 起整块被 <memory_context> 包住，标题在信封里面
        assertThat(block).contains("## 长期记忆（常驻）");
        assertThat(block).contains("回答一律用中文").doesNotContain("0.90");

        assertThat(Ranking.renderPinned(entries, 10)).isEmpty();
    }
}
