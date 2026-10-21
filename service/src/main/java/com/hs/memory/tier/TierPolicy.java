package com.hs.memory.tier;

import java.util.List;
import java.util.Locale;

/**
 * 分层的全部策略。做成纯函数，是为了让「这条记忆为什么在 T1」有一个不依赖数据库的答案。
 *
 * <p>四层的分工：
 * <ul>
 *   <li><b>T0 常驻</b>：每轮都注入，不参与检索竞争。只能显式钉住——自动晋升到 T0 会让
 *       一次偶然的重复命中永久占住预算，而且用户看不出是怎么发生的。</li>
 *   <li><b>T1 工作集</b>：当前会话正在发生的事。写入时自动进入，会话冷却后自动退出。</li>
 *   <li><b>T2 长期</b>：沉淀下来的事实与偏好，默认检索层。</li>
 *   <li><b>T3 归档</b>：长期没被访问、也没被引用过的低重要度记录。默认不召回，
 *       要显式指定 {@code tiers} 才出现——「想不起来」比「翻出一堆旧账」好。</li>
 * </ul>
 *
 * <p>晋升路径只有一条：T2 → T1（被重复命中）→ 会话结束回落 T2 → 长期不用进 T3。
 * 没有 T3 → T1 的跳级，因为那意味着一次偶然的检索就能把归档内容重新推到上下文最前面。
 */
public final class TierPolicy {

    public static final String T0 = "T0";
    public static final String T1 = "T1";
    public static final String T2 = "T2";
    public static final String T3 = "T3";
    public static final List<String> ALL = List.of(T0, T1, T2, T3);

    /** 默认检索的三层。T3 必须由调用方显式要求。 */
    public static final List<String> DEFAULT_SEARCH = List.of(T0, T1, T2);

    /** 晋升只看最近 7 天的命中：两个月前被引用过两次，不代表现在还需要它。 */
    public static final int PROMOTE_WINDOW_DAYS = 7;
    /** 归档只收「没怎么被用过」的记录；importance 高的即使很久没碰也留着。 */
    public static final double ARCHIVE_MAX_IMPORTANCE = 0.5;
    /** 复苏窗口：归档记录一旦被真的用上一次，立刻回到 T2。 */
    public static final int REVIVE_WINDOW_DAYS = 1;

    private TierPolicy() {
    }

    /**
     * 写入时的初始分层。
     *
     * <p>关键在于「谁产生了这条记忆」：一轮对话里的 episode 是正在说的话，属于工作集；
     * 抽取出来的 fact/preference 是对话的沉淀物，属于长期层；导入的文档既不是当前会话
     * 也不是沉淀结论，落到 T2 由检索决定价值。
     *
     * <p>显式给了 tier 的调用方永远优先——这是 T0 唯一的入口。
     */
    public static String initialTier(String kind, String sourceKind, boolean hasSession) {
        if ("episode".equals(kind) && "turn".equals(sourceKind) && hasSession) {
            return T1;
        }
        return T2;
    }

    /**
     * 层级加成，直接加到最终分上。
     *
     * <p>T3 是负值而不是「过滤掉」：显式检索归档时，它仍然应该排在活跃记忆后面，
     * 而不是和 T2 混在一起按名次排列。
     */
    public static double boost(String tier) {
        return switch (norm(tier)) {
            case T0 -> 0.06;
            case T1 -> 0.03;
            case T3 -> -0.12;
            default -> 0.0;
        };
    }

    public static boolean defaultSearchable(String tier) {
        return DEFAULT_SEARCH.contains(norm(tier));
    }

    /** 大小写与空值都收口到这里，避免每个调用点各写一遍。 */
    public static String norm(String tier) {
        if (tier == null || tier.isBlank()) {
            return T2;
        }
        String t = tier.trim().toUpperCase(Locale.ROOT);
        return ALL.contains(t) ? t : T2;
    }

    /** 控制台与文档共用的一句话解释。 */
    public static String describe(String tier) {
        return switch (norm(tier)) {
            case T0 -> "常驻：每轮注入，只能手工钉住";
            case T1 -> "工作集：当前会话内有效，冷却后回落 T2";
            case T3 -> "归档：默认不召回，需显式指定层级";
            default -> "长期：默认检索层";
        };
    }
}
