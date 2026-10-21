package com.hs.memory.proc;

import com.hs.memory.guard.WriteReview;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把「若干次同类任务的轨迹」压成一份策略候选。
 *
 * <p>它是纯函数：吃进行、吐出候选，不碰数据库。这样「同一批轨迹永远得出同一份策略」
 * 可以被单测钉住——蒸馏如果会随机漂移，技能就会在两次发布之间悄悄变样。
 *
 * <p>取哪条路径：<b>成功轨迹里回报最高的那条</b>，只保留信用为正的步骤。
 * 不把多条路径的步骤混着排序：A 路径的第 2 步和 B 路径的第 3 步未必能接上，
 * 混出来的是看起来像流程、实际跑不通的东西。
 */
public final class Distiller {

    private static final int MAX_STEPS = 12;
    private static final int MAX_PITFALLS = 4;

    private Distiller() {
    }

    /**
     * @param runs       同一 goal_key 下的已关闭轨迹（含成功与失败）
     * @param bestSteps  最优成功轨迹的步骤
     * @param failedRuns 失败轨迹（用于提取坑），数量由调用方限制
     * @param failedStepSets 与 failedRuns 一一对应的步骤
     */
    public static Candidate build(String goalKey, List<TrajectoryRepository.TrajectoryRow> runs,
                                  List<TrajectoryRepository.StepRow> bestSteps,
                                  List<TrajectoryRepository.TrajectoryRow> failedRuns,
                                  List<List<TrajectoryRepository.StepRow>> failedStepSets,
                                  int minSupport, double minSuccess) {
        if (runs == null || runs.isEmpty()) {
            return null;
        }
        int support = runs.size();
        int succeeded = 0;
        double rewardSum = 0;
        for (TrajectoryRepository.TrajectoryRow r : runs) {
            if ("succeeded".equals(r.status())) {
                succeeded++;
                rewardSum += r.reward() == null ? 1.0 : clamp01(r.reward());
            } else {
                rewardSum += r.reward() == null ? 0.0 : clamp01(r.reward());
            }
        }
        double successRate = (double) succeeded / support;
        double meanReturn = rewardSum / support;
        if (support < Math.max(1, minSupport) || successRate < minSuccess) {
            return null;
        }

        TrajectoryRepository.TrajectoryRow best = null;
        for (TrajectoryRepository.TrajectoryRow r : runs) {
            if (!"succeeded".equals(r.status())) {
                continue;
            }
            if (best == null || returnOf(r) > returnOf(best)) {
                best = r;
            }
        }
        if (best == null || bestSteps == null || bestSteps.isEmpty()) {
            return null;
        }

        List<Step> steps = pickSteps(bestSteps);
        if (steps.isEmpty()) {
            return null;
        }
        List<String> pitfalls = pickPitfalls(failedRuns, failedStepSets);

        // 投毒防线：策略最终会被执行，任何一步里出现命令式指令就整份隔离。
        // 只隔离不修正——「猜哪句是恶意的、删掉它」比「这次不结晶」危险得多。
        String hit = review(steps, pitfalls, bestSteps);
        return new Candidate(GoalKey.slug(goalKey), best.goal(), best.goal(), steps, pitfalls,
                support, successRate, meanReturn, hit != null, hit);
    }

    private static List<Step> pickSteps(List<TrajectoryRepository.StepRow> rows) {
        List<Step> out = new ArrayList<>();
        boolean anyCredit = rows.stream().anyMatch(r -> r.credit() != null);
        for (TrajectoryRepository.StepRow r : rows) {
            if (r.action() == null || r.action().isBlank()) {
                continue;
            }
            // 有关闭时算过信用 → 只留正贡献；没算过（老数据/手工导入）→ 全收
            if (anyCredit && r.credit() != null && r.credit() <= 0.05) {
                continue;
            }
            out.add(new Step(out.size() + 1,
                    trunc(r.action(), 240),
                    r.reflection() == null || r.reflection().isBlank() ? null : trunc(r.reflection(), 300),
                    r.observation() == null || r.observation().isBlank() ? null : trunc(r.observation(), 300)));
            if (out.size() >= MAX_STEPS) {
                break;
            }
        }
        return out;
    }

    private static List<String> pickPitfalls(List<TrajectoryRepository.TrajectoryRow> failedRuns,
                                             List<List<TrajectoryRepository.StepRow>> failedStepSets) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        if (failedStepSets != null) {
            for (List<TrajectoryRepository.StepRow> set : failedStepSets) {
                if (set == null) {
                    continue;
                }
                for (TrajectoryRepository.StepRow r : set) {
                    boolean blamed = r.credit() != null && r.credit() < -0.05;
                    if (!blamed || r.reflection() == null || r.reflection().isBlank()) {
                        continue;
                    }
                    String line = "「" + trunc(r.action(), 60) + "」" + trunc(r.reflection().trim(), 200);
                    if (seen.add(line) && out.size() < MAX_PITFALLS) {
                        out.add(line);
                    }
                }
            }
        }
        // 步骤级反思没写，就退回整条轨迹的反思——失败原因常常只在结尾被写清楚
        if (out.isEmpty() && failedRuns != null) {
            for (TrajectoryRepository.TrajectoryRow r : failedRuns) {
                String text = r.reflection();
                if (text == null || text.isBlank()) {
                    continue;
                }
                String line = trunc(text.trim(), 240);
                if (seen.add(line) && out.size() < MAX_PITFALLS) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    private static String review(List<Step> steps, List<String> pitfalls,
                                 List<TrajectoryRepository.StepRow> raw) {
        // 只审「观察 / 反思」这两段外来文本，不审 action：
        // 动作是 agent 自己做的事（「执行 docker compose up」），天生是命令式。
        for (Step s : steps) {
            String hit = check(s.why());
            if (hit != null) {
                return "反思含命令式指令（" + hit + "）";
            }
            hit = check(s.expect());
            if (hit != null) {
                return "观察含命令式指令（" + hit + "）";
            }
        }
        for (String p : pitfalls) {
            String hit = check(p);
            if (hit != null) {
                return "坑位含命令式指令（" + hit + "）";
            }
        }
        for (TrajectoryRepository.StepRow r : raw) {
            String hit = check(r.reflection());
            if (hit != null) {
                return "反思含命令式指令（" + hit + "）";
            }
        }
        return null;
    }

    private static String check(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        WriteReview.Verdict v = WriteReview.reviewProcedure(text);
        return v.hit() ? v.label() : null;
    }

    private static double returnOf(TrajectoryRepository.TrajectoryRow r) {
        return r.reward() == null ? 1.0 : clamp01(r.reward());
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    static String trunc(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** 一步策略。why = 当时为什么这么做（来自反思），expect = 做完应该看到什么（来自观察）。 */
    public record Step(int order, String action, String why, String expect) {
    }

    public record Candidate(String name, String goal, String whenToUse, List<Step> steps,
                            List<String> pitfalls, int support, double successRate, double meanReturn,
                            boolean quarantined, String quarantineReason) {
    }
}
