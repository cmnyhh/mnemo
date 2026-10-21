package com.hs.memory.proc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CreditAssignerTest {

    @Test
    void rewardDecaysBackwardsAlongTheTrajectory() {
        var steps = List.of(
                new CreditAssigner.Input(null, null),
                new CreditAssigner.Input(null, null),
                new CreditAssigner.Input(null, null));

        var r = CreditAssigner.assign(steps, 1.0, 0.5, 0);

        // 最后一步不打折地承接终局奖励，往前的每一步只剩一半
        assertThat(r.returns()).containsExactly(0.25, 0.5, 1.0);
        assertThat(r.credits()).containsExactly(0.25, 0.5, 1.0);
    }

    @Test
    void localValueIsAddedOncePerStep() {
        var steps = List.of(
                new CreditAssigner.Input(0.2, null),
                new CreditAssigner.Input(0.0, null));

        var r = CreditAssigner.assign(steps, 1.0, 0.5, 0);

        // 第一步 = 自己的 0.2 + 0.5 × 第二步的 1.0
        assertThat(r.returns()[0]).isEqualTo(0.7);
        assertThat(r.returns()[1]).isEqualTo(1.0);
    }

    @Test
    void specificReflectionAmplifiesCreditButNeverFlipsSign() {
        var vague = List.of(new CreditAssigner.Input(null, "这一步很关键"));
        var specific = List.of(new CreditAssigner.Input(null,
                "因为容器里 ELECTRON_RUN_AS_NODE=1，Electron 会退化成 Node，所以启动参数直接报错"));

        var a = CreditAssigner.assign(vague, 0.6, 0.75, 0.4);
        var b = CreditAssigner.assign(specific, 0.6, 0.75, 0.4);

        assertThat(b.credits()[0]).isGreaterThan(a.credits()[0]);
        assertThat(a.credits()[0]).isLessThanOrEqualTo(1.0);
        assertThat(b.credits()[0]).isLessThanOrEqualTo(1.0);
    }

    @Test
    void failureProducesNegativeCreditSoBlameCanBeAssigned() {
        var steps = List.of(
                new CreditAssigner.Input(null, "一开始没检查端口"),
                new CreditAssigner.Input(null, "因为端口被占用，服务起不来"));

        var r = CreditAssigner.assign(steps, -1.0, 0.75, 0.4);

        assertThat(r.credits()[0]).isNegative();
        assertThat(r.credits()[1]).isNegative();
        // 更靠近失败的那一步责任更大
        assertThat(Math.abs(r.credits()[1])).isGreaterThan(Math.abs(r.credits()[0]));
    }

    @Test
    void terminalMapsStatusToSignedReward() {
        assertThat(CreditAssigner.terminal("succeeded", null)).isEqualTo(1.0);
        assertThat(CreditAssigner.terminal("failed", null)).isEqualTo(-1.0);
        assertThat(CreditAssigner.terminal("failed", 0.6)).isEqualTo(-0.4);
        assertThat(CreditAssigner.terminal("abandoned", null)).isEqualTo(0.0);
        assertThatThrownBy(() -> CreditAssigner.terminal("unknown", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyTrajectoryIsHarmless() {
        var r = CreditAssigner.assign(List.of(), 1.0, 0.75, 0.4);
        assertThat(r.size()).isZero();
    }
}
