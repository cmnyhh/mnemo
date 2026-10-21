package com.hs.memory.proc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReflectionWeightTest {

    @Test
    void emptyReflectionCarriesNoWeight() {
        assertThat(ReflectionWeight.of(null)).isZero();
        assertThat(ReflectionWeight.of("   ")).isZero();
    }

    @Test
    void vaguePraiseWeighsLessThanACausalExplanation() {
        double vague = ReflectionWeight.of("这一步很关键");
        double causal = ReflectionWeight.of("因为容器里 ELECTRON_RUN_AS_NODE=1，Electron 退化成 Node");
        assertThat(causal).isGreaterThan(vague);
    }

    @Test
    void remedyAndConcreteObjectsPushWeightUp() {
        double plain = ReflectionWeight.of("端口不对");
        double full = ReflectionWeight.of(
                "因为 .env 里 QDRANT_API_KEY 与服务容器的 MEMORY_QDRANT_API_KEY 是两个名字，"
                        + "所以连不上；下次应该先对齐三个环境变量");
        assertThat(full).isGreaterThan(plain);
        assertThat(full).isLessThanOrEqualTo(1.0);
    }

    @Test
    void weightIsBounded() {
        String longText = "因为 A=1 导致 B，所以应该改为 C 并避免 D，".repeat(20);
        assertThat(ReflectionWeight.of(longText)).isEqualTo(1.0);
    }

    @Test
    void overallAveragesOnlyTheWrittenReflections() {
        assertThat(ReflectionWeight.overall(java.util.Arrays.asList(null, "", "因为 A 所以 B")))
                .isGreaterThan(0);
        assertThat(ReflectionWeight.overall(java.util.List.of())).isZero();
    }
}
