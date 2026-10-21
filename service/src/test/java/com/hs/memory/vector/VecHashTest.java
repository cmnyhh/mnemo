package com.hs.memory.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VecHashTest {

    private static final float[] V = {0.5f, -0.25f, 0.125f};

    @Test
    void 同一空间同一向量摘要稳定() {
        assertThat(VecHash.of("space-a", V)).isEqualTo(VecHash.of("space-a", V.clone()));
    }

    @Test
    void 换空间摘要就不同() {
        // 对账要防的正是「换了模型但向量没重建」，所以 space 必须进摘要
        assertThat(VecHash.of("space-a", V)).isNotEqualTo(VecHash.of("space-b", V));
    }

    @Test
    void 向量变一点摘要就不同() {
        float[] other = {0.5f, -0.25f, 0.126f};
        assertThat(VecHash.of("space-a", V)).isNotEqualTo(VecHash.of("space-a", other));
    }

    @Test
    void 空向量没有摘要() {
        assertThatThrownBy(() -> VecHash.of("space-a", new float[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
