package com.hs.memory.proc;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 一步反思的「信息量」权重，取值 0..1。
 *
 * <p>为什么不用语义模型：反思是每条轨迹关闭时都要算一次的东西，这里再加一次 LLM 调用
 * 既慢又不稳定。真正决定权重的是三个可判定的特征——<b>有没有解释因果</b>、
 * <b>有没有具体对象</b>、<b>有没有给出改法</b>。「这一步很关键」这类空话权重就低，
 * 「因为容器里 ELECTRON_RUN_AS_NODE=1，Electron 退化成 Node」权重就高。
 *
 * <p>权重只放大信用，不改变信用的符号：做对的一步反思越具体，功劳越大；
 * 做错的一步反思越具体，责任越大。这正是「反思加权」要的效果。
 */
public final class ReflectionWeight {

    /** 因果连接词：说明「为什么会这样」，这是反思最核心的部分。 */
    private static final Pattern CAUSAL = Pattern.compile(
            "因为|由于|所以|因此|导致|原因是|之所以|原来|根因|根本原因|"
                    + "because|since|therefore|so that|root cause|caused by|turns out",
            Pattern.CASE_INSENSITIVE);

    /** 改法：出现它说明反思落到了「下次怎么办」，可以直接进策略。 */
    private static final Pattern REMEDY = Pattern.compile(
            "应该|应当|改为|改用|换成|避免|不要|必须先|需要先|下次|以后|instead|should|must|next time",
            Pattern.CASE_INSENSITIVE);

    /** 具体对象：数字、路径、URL、代码标识符、引号里的内容。 */
    private static final Pattern CONCRETE = Pattern.compile(
            "\\d|[A-Za-z_][A-Za-z0-9_.-]{2,}|[/\\\\][\\w.-]+|[\"'`「『][^\"'`」』]{2,}[\"'`」』]");

    private ReflectionWeight() {
    }

    public static double of(String reflection) {
        if (reflection == null) {
            return 0;
        }
        String text = reflection.trim();
        if (text.isEmpty()) {
            return 0;
        }
        double w = 0.35;
        if (CAUSAL.matcher(text).find()) {
            w += 0.20;
        }
        if (CONCRETE.matcher(text).find()) {
            w += 0.20;
        }
        if (REMEDY.matcher(text).find()) {
            w += 0.15;
        }
        if (text.length() >= 40) {
            w += 0.10;
        }
        return Math.min(1.0, w);
    }

    /** 一条轨迹的整体反思权重，用于判断这条轨迹值不值得拿来蒸馏。 */
    public static double overall(List<String> reflections) {
        if (reflections == null || reflections.isEmpty()) {
            return 0;
        }
        double sum = 0;
        int n = 0;
        for (String r : reflections) {
            if (r != null && !r.isBlank()) {
                sum += of(r);
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }
}
