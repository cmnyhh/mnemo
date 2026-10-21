package com.hs.memory.proc;

import java.util.List;

/**
 * 信用回传：终局奖励沿轨迹倒着分给每一步。
 *
 * <p>公式（折扣回报 + 反思加权）：
 * <pre>
 *   G[t]   = r[t] + γ · (t 是最后一步 ? R : G[t+1])
 *   h[t]   = 该步反思的信息量 (0..1)
 *   credit = clamp(G[t] · (1 + λ · h[t]), -1, 1)
 * </pre>
 *
 * <p>三个刻意选择：
 * <ul>
 *   <li><b>倒序回传</b>：越靠近终局的动作拿到越多信用。这是对的——最后一步往往
 *       直接决定成败，而第一步只是「开工」。</li>
 *   <li><b>反思加权是乘法不是加法</b>：没有反思的步骤保持原始回报，不会被凭空抬高；
 *       反思越具体，功劳/责任越大。加法会让「写一句反思」本身就刷分。</li>
 *   <li><b>clamp 在 [-1,1]</b>：折扣回报在 |R| &lt;= 1、|r| &lt;= 1 时本来就不会越界，
 *       加权才会。夹住是为了让 credit 能直接当「贡献度」展示和排序，不用二次归一。</li>
 * </ul>
 */
public final class CreditAssigner {

    /** 一步的输入。value 是调用方给的局部奖励（可空 = 0），reflection 用来算权重。 */
    public record Input(Double value, String reflection) {
    }

    /** 与输入等长的三个数组：反思权重、折扣回报、最终信用。 */
    public record Result(double[] weights, double[] returns, double[] credits) {

        public int size() {
            return credits.length;
        }
    }

    private CreditAssigner() {
    }

    public static Result assign(List<Input> steps, double terminalReward, double gamma, double reflectionBonus) {
        int n = steps == null ? 0 : steps.size();
        double[] w = new double[n];
        double[] g = new double[n];
        double[] c = new double[n];
        if (n == 0) {
            return new Result(w, g, c);
        }
        double discount = clamp(gamma, 0, 1);
        double bonus = Math.max(0, reflectionBonus);
        double terminal = clamp(terminalReward, -1, 1);

        for (int i = n - 1; i >= 0; i--) {
            Input in = steps.get(i);
            double local = in == null || in.value() == null ? 0 : clamp(in.value(), -1, 1);
            // 最后一步直接承接终局奖励，不打折；更早的步按 γ 衰减
            double downstream = i == n - 1 ? terminal : discount * g[i + 1];
            g[i] = clamp(local + downstream, -1, 1);
            w[i] = in == null ? 0 : ReflectionWeight.of(in.reflection());
            c[i] = clamp(g[i] * (1 + bonus * w[i]), -1, 1);
        }
        return new Result(w, g, c);
    }

    /**
     * 把「调用方给的终局状态 + 0..1 奖励」翻译成有符号的终端回报。
     *
     * <p>失败必须变成负数，否则整条轨迹的信用全是 0，学不到「哪一步搞砸了」。
     * 部分完成（reward=0.6 但最终失败）记 -0.4：既惩戒，也保留「前面确实推进过」的信息。
     */
    public static double terminal(String status, Double reward) {
        double r = reward == null ? Double.NaN : clamp(reward, 0, 1);
        return switch (status) {
            case "succeeded" -> Double.isNaN(r) ? 1.0 : r;
            case "failed" -> Double.isNaN(r) ? -1.0 : -(1.0 - r);
            case "abandoned" -> 0.0;
            default -> throw new IllegalArgumentException("未知的轨迹状态: " + status);
        };
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
