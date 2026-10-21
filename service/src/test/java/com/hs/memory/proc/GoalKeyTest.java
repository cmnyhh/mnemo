package com.hs.memory.proc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoalKeyTest {

    @Test
    void stripsPunctuationAndCase() {
        assertThat(GoalKey.of("把 mnemo 部署到 2 核服务器！")).isEqualTo("把 mnemo 部署到 2 核服务器");
        assertThat(GoalKey.of("  部署　MNEMO  ")).isEqualTo("部署 mnemo");
        assertThat(GoalKey.of("Fix: the  502!")).isEqualTo("fix the 502");
    }

    @Test
    void truncatesLongGoals() {
        String key = GoalKey.of("部署".repeat(100));
        assertThat(key.length()).isLessThanOrEqualTo(48);
    }

    @Test
    void emptyGoalYieldsEmptyKey() {
        assertThat(GoalKey.of("！？。 ")).isEmpty();
        assertThat(GoalKey.of(null)).isEmpty();
    }

    @Test
    void slugIsShortAsciiAndStable() {
        String a = GoalKey.slug("部署mnemo");
        String b = GoalKey.slug("部署mnemo");
        assertThat(a).isEqualTo(b).matches("[a-z0-9-]{3,64}");
        assertThat(GoalKey.slug("另一件事")).isNotEqualTo(a);
    }
}
