package com.hs.memory.proc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.memory.Settings;
import com.hs.memory.obs.AuditService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 程序性记忆的主流程：记轨迹 → 关轨迹时回传信用 → 蒸馏策略 → 维护世界模型 → 结晶 Skill。
 *
 * <p>三层的关系是「越来越慢、越来越少」：
 * <ul>
 *   <li>L1 每次执行都写，量大、易变，只在排查时看；</li>
 *   <li>L2 同类任务重复几次才出现，是「做法」；</li>
 *   <li>L3 只记动作与预期，用来在动手前判断「这样做会发生什么」；</li>
 *   <li>Skill 是 L2 的成品，门槛最高——它会被真的照着执行。</li>
 * </ul>
 *
 * <p>所有写操作都按 tenant 过滤；读也一样。RLS 是第二道，不是唯一一道。
 */
@Service
public class ProcedureService {

    private static final Logger log = LoggerFactory.getLogger(ProcedureService.class);
    private static final int MAX_STEPS_PER_CALL = 64;
    private static final int MAX_GOAL_CHARS = 500;

    private final TrajectoryRepository repo;
    private final Settings settings;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public ProcedureService(TrajectoryRepository repo, Settings settings, ObjectMapper mapper,
                            AuditService audit) {
        this.repo = repo;
        this.settings = settings;
        this.mapper = mapper;
        this.audit = audit;
    }

    // ---- L1 ----

    @Transactional
    public OpenResult open(String tenant, String userId, String projectId, String sessionId, String goal) {
        requireEnabled();
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal 不能为空");
        }
        String trimmed = goal.strip();
        if (trimmed.length() > MAX_GOAL_CHARS) {
            throw new IllegalArgumentException("goal 最长 " + MAX_GOAL_CHARS + " 字符");
        }
        String goalKey = GoalKey.of(trimmed);
        if (goalKey.isEmpty()) {
            throw new IllegalArgumentException("goal 里没有可用字符");
        }
        UUID id = repo.create(tenant, userId, projectId, sessionId, trimmed, goalKey);
        return new OpenResult(id.toString(), goalKey, "open");
    }

    @Transactional
    public AppendResult append(String tenant, String id, List<StepCommand> steps) {
        requireEnabled();
        UUID uuid = uuid(id);
        TrajectoryRepository.TrajectoryRow run = repo.find(tenant, uuid)
                .orElseThrow(() -> new IllegalArgumentException("轨迹不存在: " + id));
        if (!"open".equals(run.status())) {
            throw new IllegalArgumentException("轨迹已关闭，不能再追加步骤");
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("steps 不能为空");
        }
        if (steps.size() > MAX_STEPS_PER_CALL) {
            throw new IllegalArgumentException("单次最多追加上限 " + MAX_STEPS_PER_CALL + " 步");
        }
        int idx = repo.nextIndex(tenant, uuid);
        for (StepCommand s : steps) {
            if (s == null || s.action() == null || s.action().isBlank()) {
                throw new IllegalArgumentException("每一步的 action 都不能为空");
            }
            repo.appendStep(tenant, uuid, idx++, s.action().strip(),
                    s.observation(), s.reflection(), s.value());
        }
        repo.syncStepCount(tenant, uuid);
        return new AppendResult(steps.size(), idx);
    }

    /**
     * 关闭轨迹并回传信用。
     *
     * <p>顺序是刻意的：先把信用算完写库，再做世界模型与蒸馏。后面两步失败只记日志，
     * 不影响「这条轨迹已经结束」这个事实——轨迹本身是第一手证据，不能因为总结失败而丢。
     */
    @Transactional
    public CloseResult close(String tenant, String id, String status, Double reward, String reflection) {
        requireEnabled();
        UUID uuid = uuid(id);
        TrajectoryRepository.TrajectoryRow run = repo.find(tenant, uuid)
                .orElseThrow(() -> new IllegalArgumentException("轨迹不存在: " + id));
        if (!"open".equals(run.status())) {
            throw new IllegalArgumentException("轨迹已关闭: " + run.status());
        }
        String finalStatus = status == null || status.isBlank() ? "succeeded" : status.strip();
        if (!List.of("succeeded", "failed", "abandoned").contains(finalStatus)) {
            throw new IllegalArgumentException("status 只能是 succeeded / failed / abandoned");
        }
        List<TrajectoryRepository.StepRow> steps = repo.steps(tenant, uuid);

        double terminal = CreditAssigner.terminal(finalStatus, reward);
        List<CreditAssigner.Input> inputs = steps.stream()
                .map(s -> new CreditAssigner.Input(s.value(), s.reflection()))
                .toList();
        CreditAssigner.Result credit = CreditAssigner.assign(inputs, terminal, settings.procGamma(),
                settings.procReflectionBonus());

        if (credit.size() > 0) {
            repo.saveCredits(tenant, uuid, credit.weights(), credit.credits());
        }
        Double stored = reward == null ? ("succeeded".equals(finalStatus) ? 1.0
                : ("failed".equals(finalStatus) ? 0.0 : null)) : clamp01(reward);
        boolean closed = repo.close(tenant, uuid, finalStatus, stored, reflection);
        if (!closed) {
            throw new IllegalArgumentException("轨迹已被其它请求关闭");
        }

        int world = 0;
        try {
            world = updateWorld(tenant, run.goalKey(), steps, credit);
        } catch (Exception e) {
            log.warn("世界模型更新失败 {}: {}", id, e.getMessage());
        }

        StrategyBrief strategy = null;
        SkillBrief skill = null;
        boolean quarantined = false;
        String note = null;
        try {
            DistillOutcome outcome = distill(tenant, run.goalKey());
            if (outcome != null) {
                strategy = outcome.strategy();
                skill = outcome.skill();
                quarantined = outcome.quarantined();
                note = outcome.note();
            }
        } catch (Exception e) {
            note = "已关闭，但策略蒸馏失败: " + e.getMessage();
            log.warn("策略蒸馏失败 {}: {}", id, e.getMessage());
        }

        List<StepCredit> credits = new ArrayList<>();
        for (int i = 0; i < credit.size() && i < steps.size(); i++) {
            credits.add(new StepCredit(steps.get(i).idx(), trunc(steps.get(i).action(), 80),
                    round(credit.credits()[i]), round(credit.weights()[i])));
        }
        return new CloseResult(uuid.toString(), finalStatus, steps.size(), round(terminal), credits,
                strategy, skill, world, quarantined, note);
    }

    /**
     * 一次性写入整条轨迹：开局 → 追加 → 关闭。给「任务已经做完、只汇报结果」的调用方用。
     *
     * <p>它不替代分三步的用法：agent 边走边记时，中途崩溃留下的半条轨迹恰恰是最该学的样本，
     * 而一次性写入只存在于成功汇报的场景。
     */
    @Transactional
    public CloseResult record(String tenant, String userId, String projectId, String sessionId,
                              String goal, List<StepCommand> steps, String status,
                              Double reward, String reflection) {
        OpenResult opened = open(tenant, userId, projectId, sessionId, goal);
        if (steps != null && !steps.isEmpty()) {
            for (int i = 0; i < steps.size(); i += MAX_STEPS_PER_CALL) {
                int end = Math.min(steps.size(), i + MAX_STEPS_PER_CALL);
                append(tenant, opened.id(), steps.subList(i, end));
            }
        }
        return close(tenant, opened.id(), status, reward, reflection);
    }

    public TrajectoryDetail detail(String tenant, String id) {
        UUID uuid = uuid(id);
        TrajectoryRepository.TrajectoryRow run = repo.find(tenant, uuid)
                .orElseThrow(() -> new IllegalArgumentException("轨迹不存在: " + id));
        return new TrajectoryDetail(run, repo.steps(tenant, uuid));
    }

    public List<TrajectoryRepository.TrajectoryRow> recent(String tenant, int limit) {
        return repo.recent(tenant, limit);
    }

    // ---- 蒸馏 ----

    /** 手动触发一次全量蒸馏，给控制台的「立即学习」按钮用。 */
    public LearnResult learnAll(String tenant) {
        requireEnabled();
        List<TrajectoryRepository.GoalCount> keys = repo.goalKeys(tenant, 90, 200);
        int strategies = 0;
        int skills = 0;
        int quarantined = 0;
        for (TrajectoryRepository.GoalCount k : keys) {
            try {
                DistillOutcome out = distill(tenant, k.goalKey());
                if (out == null) {
                    continue;
                }
                if (out.quarantined()) {
                    quarantined++;
                }
                if (out.strategy() != null) {
                    strategies++;
                }
                if (out.skill() != null) {
                    skills++;
                }
            } catch (Exception e) {
                log.warn("蒸馏 {} 失败: {}", k.goalKey(), e.getMessage());
            }
        }
        audit.record("proc.distill", null, Map.of("goalKeys", keys.size(), "strategies", strategies,
                "skills", skills, "quarantined", quarantined));
        return new LearnResult(keys.size(), strategies, skills, quarantined);
    }

    private DistillOutcome distill(String tenant, String goalKey) {
        List<TrajectoryRepository.TrajectoryRow> runs = repo.closedRuns(tenant, goalKey, 50);
        if (runs.isEmpty()) {
            return null;
        }
        TrajectoryRepository.TrajectoryRow best = bestOf(runs);
        if (best == null) {
            return null;
        }
        List<TrajectoryRepository.StepRow> bestSteps = repo.steps(tenant, best.id());

        List<TrajectoryRepository.TrajectoryRow> failedRuns = new ArrayList<>();
        List<List<TrajectoryRepository.StepRow>> failedSteps = new ArrayList<>();
        for (TrajectoryRepository.TrajectoryRow r : runs) {
            if (!"failed".equals(r.status()) || failedRuns.size() >= 3) {
                continue;
            }
            failedRuns.add(r);
            failedSteps.add(repo.steps(tenant, r.id()));
        }

        Distiller.Candidate c = Distiller.build(goalKey, runs, bestSteps, failedRuns, failedSteps,
                settings.procMinSupport(), settings.procMinSuccess());
        if (c == null) {
            return null;
        }

        String status = c.quarantined() ? "quarantined" : "active";
        String stepsJson = json(c.steps());
        String pitfallsJson = json(c.pitfalls());
        UUID strategyId = repo.upsertStrategy(tenant, goalKey, c.name(), c.goal(), c.whenToUse(),
                stepsJson, pitfallsJson, c.support(), c.successRate(), c.meanReturn(), status);
        repo.linkStrategyToRun(tenant, best.id(), strategyId);

        SkillBrief skill = null;
        if (!c.quarantined()) {
            skill = crystallize(tenant, strategyId, c);
        }
        return new DistillOutcome(
                new StrategyBrief(strategyId.toString(), c.name(), status, c.support(), round(c.successRate())),
                skill, c.quarantined(), c.quarantineReason());
    }

    /**
     * 结晶。同一份技能只在新证据改变内容时升版本——否则每次成功都会多一版，
     * 版本号就不再表示「它变好了」，只是「又跑了一次」。
     */
    private SkillBrief crystallize(String tenant, UUID strategyId, Distiller.Candidate c) {
        if (c.support() < settings.procCrystallizeSupport()
                || c.successRate() < settings.procCrystallizeSuccess()) {
            return null;
        }
        String name = c.name();
        int version = repo.nextSkillVersion(tenant, name);
        String body = SkillWriter.render(name, c.goal(), version, c);
        Optional<TrajectoryRepository.SkillRow> latest = repo.latestSkillByStrategy(tenant, strategyId);
        if (latest.isPresent() && sameBody(latest.get().body(), body)) {
            return new SkillBrief(name, latest.get().version(), false);
        }
        String evidence = json(Map.of(
                "support", c.support(),
                "successRate", round(c.successRate()),
                "meanReturn", round(c.meanReturn()),
                "pitfalls", c.pitfalls().size()));
        UUID skillId = repo.insertSkill(tenant, name, c.goal(), c.goal(), body, version, strategyId, evidence);
        repo.retireOlderSkills(tenant, name, version);
        repo.linkSkillToStrategy(tenant, strategyId, skillId);
        audit.record("proc.crystallize", name, Map.of("version", version, "support", c.support()));
        log.info("技能结晶 {} v{}（支撑 {} 次，成功率 {}）", name, version, c.support(), round(c.successRate()));
        return new SkillBrief(name, version, true);
    }

    /** 版本号不同，正文必然不同；比较时把 version 行去掉，避免「只因为版本号变了就升版」。 */
    private static boolean sameBody(String oldBody, String newBody) {
        return stripVersion(oldBody).equals(stripVersion(newBody));
    }

    private static String stripVersion(String body) {
        return body == null ? "" : body.replaceAll("(?m)^version:.*$", "");
    }

    // ---- L3 ----

    private int updateWorld(String tenant, String goalKey, List<TrajectoryRepository.StepRow> steps,
                            CreditAssigner.Result credit) {
        int n = 0;
        for (int i = 0; i < steps.size() && i < credit.size(); i++) {
            TrajectoryRepository.StepRow s = steps.get(i);
            if (s.observation() == null || s.observation().strip().length() < 4) {
                continue;
            }
            String subject = subjectOf(s.action(), goalKey);
            String action = trunc(s.action(), 160);
            String expect = trunc(s.observation(), 300);
            double c = credit.credits()[i];
            if (c >= 0.25) {
                repo.observeWorld(tenant, subject, action, expect);
                n++;
            } else if (c <= -0.15) {
                repo.contradictWorld(tenant, subject, action, expect);
                n++;
            }
        }
        return n;
    }

    /** 世界模型的「主语」取动作的第一个词（工具/对象名）；取不到就退回任务签名。 */
    static String subjectOf(String action, String goalKey) {
        String a = action == null ? "" : action.strip().toLowerCase();
        int cut = a.length();
        for (int i = 0; i < a.length(); i++) {
            char ch = a.charAt(i);
            if (Character.isWhitespace(ch) || ch == '：' || ch == ':' || ch == '（' || ch == '(') {
                cut = i;
                break;
            }
        }
        String s = a.substring(0, cut).trim();
        if (s.length() < 2) {
            s = goalKey == null ? "task" : goalKey;
        }
        return trunc(s, 40);
    }

    public List<TrajectoryRepository.WorldRow> world(String tenant, String subject, int limit) {
        return repo.world(tenant, subject, limit);
    }

    // ---- L2 / Skill 查询 ----

    public List<StrategyView> strategies(String tenant, int limit) {
        List<StrategyView> out = new ArrayList<>();
        for (TrajectoryRepository.StrategyRow r : repo.strategies(tenant, limit)) {
            out.add(new StrategyView(r.id(), r.goalKey(), r.name(), r.goal(), r.whenToUse(),
                    tree(r.stepsJson()), tree(r.pitfallsJson()), r.support(), round(r.successRate()),
                    round(r.meanReturn()), r.status(), r.skillId(), r.updatedAt()));
        }
        return out;
    }

    public List<SkillView> skills(String tenant, int limit) {
        List<SkillView> out = new ArrayList<>();
        for (TrajectoryRepository.SkillRow r : repo.skills(tenant, limit)) {
            out.add(new SkillView(r.name(), r.title(), r.description(), r.version(),
                    tree(r.evidenceJson()), r.updatedAt()));
        }
        return out;
    }

    public Optional<TrajectoryRepository.SkillRow> skill(String tenant, String name) {
        return repo.activeSkill(tenant, name);
    }

    // ---- 杂项 ----

    private void requireEnabled() {
        if (!settings.procEnabled()) {
            // 用 400 而不是 500：这是部署开关，不是服务故障，调用方改请求也不会好
            throw new IllegalArgumentException("程序性记忆已关闭（MEMORY_PROC_ENABLED=false）");
        }
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("id 需为 uuid");
        }
    }

    private static TrajectoryRepository.TrajectoryRow bestOf(
            List<TrajectoryRepository.TrajectoryRow> runs) {
        TrajectoryRepository.TrajectoryRow best = null;
        for (TrajectoryRepository.TrajectoryRow r : runs) {
            if (!"succeeded".equals(r.status())) {
                continue;
            }
            if (best == null || returnOf(r) > returnOf(best)) {
                best = r;
            }
        }
        return best;
    }

    private static double returnOf(TrajectoryRepository.TrajectoryRow r) {
        return r.reward() == null ? 1.0 : r.reward();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败: " + e.getMessage(), e);
        }
    }

    private JsonNode tree(String raw) {
        try {
            return raw == null || raw.isBlank() ? mapper.createArrayNode() : mapper.readTree(raw);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String trunc(String s, int max) {
        return Distiller.trunc(s, max);
    }

    // ---- 类型 ----

    public record OpenResult(String id, String goalKey, String status) {
    }

    public record AppendResult(int added, int stepCount) {
    }

    /** 一步的局部输入。value 可空，reflection 决定这一步的反思权重。 */
    public record StepCommand(String action, String observation, String reflection, Double value) {
    }

    public record StepCredit(int idx, String action, double credit, double reflectionWeight) {
    }

    public record StrategyBrief(String id, String name, String status, int support, double successRate) {
    }

    public record SkillBrief(String name, int version, boolean newVersion) {
    }

    public record CloseResult(String id, String status, int steps, double terminalReward,
                              List<StepCredit> credits, StrategyBrief strategy, SkillBrief skill,
                              int worldRules, boolean quarantined, String note) {
    }

    public record TrajectoryDetail(TrajectoryRepository.TrajectoryRow trajectory,
                                   List<TrajectoryRepository.StepRow> steps) {
    }

    public record StrategyView(UUID id, String goalKey, String name, String goal, String whenToUse,
                               JsonNode steps, JsonNode pitfalls, int support, double successRate,
                               double meanReturn, String status, UUID skillId, Instant updatedAt) {
    }

    public record SkillView(String name, String title, String description, int version,
                            JsonNode evidence, Instant updatedAt) {
    }

    public record LearnResult(int goalKeys, int strategies, int skills, int quarantined) {
    }

    private record DistillOutcome(StrategyBrief strategy, SkillBrief skill,
                                  boolean quarantined, String note) {
    }
}
