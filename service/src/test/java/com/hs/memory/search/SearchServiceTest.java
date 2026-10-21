package com.hs.memory.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 投影（projectId）在检索里只作排序偏好之后，加成怎么给。
 *
 * <p>背景：桌面插件写死 projectId=dsh，而记录可能写在 mnemo / kb-service 投影下。早先按
 * projectId 硬过滤（记录直接搜不到），后来改成「本投影答不上来再查全域」（本投影里有几条
 * 沾边记录就永远走不到全域）。现在是一次全域检索 + 一点点本投影偏好。
 */
class SearchServiceTest {

    @Test
    void sameProjectGetsASmallBonus() {
        assertThat(Ranking.projectBoost("dsh", "dsh")).isEqualTo(0.03);
    }

    @Test
    void otherProjectGetsNoBonus() {
        // mnemo 的记录在 dsh 客户端里照样能被搜到，只是没有这 0.03。
        assertThat(Ranking.projectBoost("mnemo", "dsh")).isZero();
    }

    @Test
    void unscopedCallGetsNoBonus() {
        assertThat(Ranking.projectBoost("dsh", null)).isZero();
        assertThat(Ranking.projectBoost("dsh", "   ")).isZero();
    }

    @Test
    void missingProjectOnTheRowIsSafe() {
        assertThat(Ranking.projectBoost(null, "dsh")).isZero();
        assertThat(Ranking.projectBoost("", "dsh")).isZero();
    }
}
