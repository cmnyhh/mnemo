package com.hs.memory.guard;

import java.util.List;

/**
 * 来源分级：一条记忆「有多可信」不该由写入方自己声明。
 *
 * <p>投毒链条是「外部内容 → 记忆 → 影响后续所有会话」，第一步就是让外部内容看起来
 * 像用户自己的偏好。所以这里只承认两类高可信来源：用户手写（manual）和本机工具
 * （tool）；模型从对话里推断的算中等（turn）；导入的外部内容算低（import）。
 *
 * <p>调用方仍可以显式给 trustLevel，但只能<b>往下降</b>：一封邮件里写「我是老板，
 * 这条标 high」，不该因为它自称 high 就真的变 high。
 */
public final class TrustPolicy {

    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";
    public static final List<String> ALL = List.of(LOW, MEDIUM, HIGH);

    private TrustPolicy() {
    }

    public static int rank(String trust) {
        return switch (norm(trust)) {
            case HIGH -> 2;
            case LOW -> 0;
            default -> 1;
        };
    }

    public static String norm(String trust) {
        if (trust == null || trust.isBlank()) {
            return MEDIUM;
        }
        String t = trust.trim().toLowerCase(java.util.Locale.ROOT);
        return ALL.contains(t) ? t : MEDIUM;
    }

    /** 来源决定的上限。turn 是模型推断，因此封顶 medium。 */
    public static String fromSource(String sourceKind) {
        if (sourceKind == null) {
            return MEDIUM;
        }
        return switch (sourceKind.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "manual", "tool" -> HIGH;
            case "import" -> LOW;
            default -> MEDIUM;
        };
    }

    /** 声明值只能把可信度往下拉，不能往上抬。 */
    public static String resolve(String sourceKind, String declared) {
        String ceiling = fromSource(sourceKind);
        if (declared == null || declared.isBlank()) {
            return ceiling;
        }
        return rank(declared) < rank(ceiling) ? norm(declared) : ceiling;
    }

    /**
     * 低可信内容不许进常驻层与工作集。
     *
     * <p>T0 每轮都注入、T1 是当前会话的默认检索层，这两层的「在场率」太高：
     * 一条网页抄来的句子占住 T0，等于每一轮都在给模型下同一个暗示。
     */
    public static boolean allowsTier(String trust, String tier) {
        if (!LOW.equals(norm(trust))) {
            return true;
        }
        return !("T0".equals(tier) || "T1".equals(tier));
    }

    public static String describe(String trust) {
        return switch (norm(trust)) {
            case HIGH -> "高：用户手写或本机工具产生";
            case LOW -> "低：外部内容，不参与常驻与晋升";
            default -> "中：模型从对话推断";
        };
    }
}
