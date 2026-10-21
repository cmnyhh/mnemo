package com.hs.memory.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrustPolicyTest {

    @Test
    void sourceSetsTheCeiling() {
        assertThat(TrustPolicy.fromSource("manual")).isEqualTo("high");
        assertThat(TrustPolicy.fromSource("tool")).isEqualTo("high");
        assertThat(TrustPolicy.fromSource("turn")).isEqualTo("medium");
        assertThat(TrustPolicy.fromSource("import")).isEqualTo("low");
        assertThat(TrustPolicy.fromSource(null)).isEqualTo("medium");
    }

    @Test
    void declaredTrustCanOnlyGoDown() {
        assertThat(TrustPolicy.resolve("turn", "high")).isEqualTo("medium");
        assertThat(TrustPolicy.resolve("turn", "low")).isEqualTo("low");
        assertThat(TrustPolicy.resolve("import", "high")).isEqualTo("low");
        assertThat(TrustPolicy.resolve("manual", null)).isEqualTo("high");
        assertThat(TrustPolicy.resolve("manual", "medium")).isEqualTo("medium");
    }

    @Test
    void lowTrustCannotReachResidentOrWorkingTiers() {
        assertThat(TrustPolicy.allowsTier("low", "T0")).isFalse();
        assertThat(TrustPolicy.allowsTier("low", "T1")).isFalse();
        assertThat(TrustPolicy.allowsTier("low", "T2")).isTrue();
        assertThat(TrustPolicy.allowsTier("medium", "T0")).isTrue();
        assertThat(TrustPolicy.allowsTier("high", "T1")).isTrue();
    }
}
