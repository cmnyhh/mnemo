package com.hs.memory.proc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 程序性记忆的读写。四张表各管一层，没有跨层的联表查询：
 * 轨迹页要看步骤、策略页要看统计、技能页要看正文，它们的读取频率和形状都不一样。
 */
@Repository
public class TrajectoryRepository {

    private final JdbcTemplate jdbc;

    public TrajectoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- L1 轨迹 ----

    public UUID create(String tenantId, String userId, String projectId, String sessionId,
                       String goal, String goalKey) {
        return jdbc.queryForObject("""
                insert into proc_trajectory (tenant_id, user_id, project_id, session_id, goal, goal_key)
                values (?, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class, tenantId, nz(userId), projectId == null || projectId.isBlank() ? null : projectId,
                sessionId == null || sessionId.isBlank() ? null : sessionId, goal, goalKey);
    }

    public Optional<TrajectoryRow> find(String tenantId, UUID id) {
        List<TrajectoryRow> rows = jdbc.query(
                "select * from proc_trajectory where tenant_id = ? and id = ?", TRAJ_MAPPER, tenantId, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<TrajectoryRow> recent(String tenantId, int limit) {
        return jdbc.query("""
                select * from proc_trajectory
                 where tenant_id = ?
                 order by started_at desc
                 limit ?
                """, TRAJ_MAPPER, tenantId, Math.max(1, limit));
    }

    /** 续写时的下一个序号。取 max+1 而不是用 count：删过步之后 count 会和实际序号撞车。 */
    public int nextIndex(String tenantId, UUID trajectoryId) {
        Integer next = jdbc.queryForObject("""
                select coalesce(max(idx), -1) + 1 from proc_step
                 where tenant_id = ? and trajectory_id = ?
                """, Integer.class, tenantId, trajectoryId);
        return next == null ? 0 : next;
    }

    public void appendStep(String tenantId, UUID trajectoryId, int idx, String action,
                           String observation, String reflection, Double value) {
        jdbc.update("""
                insert into proc_step (tenant_id, trajectory_id, idx, action, observation, reflection, value)
                values (?, ?, ?, ?, ?, ?, ?)
                """, tenantId, trajectoryId, idx, action, nz(observation), reflection, value);
    }

    public void syncStepCount(String tenantId, UUID trajectoryId) {
        jdbc.update("""
                update proc_trajectory
                   set step_count = (select count(*) from proc_step
                                      where tenant_id = ? and trajectory_id = ?)
                 where tenant_id = ? and id = ?
                """, tenantId, trajectoryId, tenantId, trajectoryId);
    }

    public List<StepRow> steps(String tenantId, UUID trajectoryId) {
        return jdbc.query("""
                select idx, action, observation, reflection, value, reflection_weight, credit
                  from proc_step
                 where tenant_id = ? and trajectory_id = ?
                 order by idx
                """, STEP_MAPPER, tenantId, trajectoryId);
    }

    /** 回传结果落库。按 idx 定位，不依赖 id，调用方拿到的顺序与写入顺序一致。 */
    public void saveCredits(String tenantId, UUID trajectoryId, double[] weights, double[] credits) {
        int n = Math.min(weights.length, credits.length);
        jdbc.batchUpdate("""
                update proc_step set reflection_weight = ?, credit = ?
                 where tenant_id = ? and trajectory_id = ? and idx = ?
                """, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws SQLException {
                ps.setDouble(1, weights[i]);
                ps.setDouble(2, credits[i]);
                ps.setString(3, tenantId);
                ps.setObject(4, trajectoryId);
                ps.setInt(5, i);
            }

            @Override
            public int getBatchSize() {
                return n;
            }
        });
    }

    /** 关闭轨迹。只有 open 能被关闭：重复关闭返回 false，由上层决定是 409 还是幂等返回。 */
    public boolean close(String tenantId, UUID id, String status, Double reward, String reflection) {
        int n = jdbc.update("""
                update proc_trajectory
                   set status = ?, reward = ?, reflection = ?,
                       step_count = (select count(*) from proc_step
                                      where tenant_id = ? and trajectory_id = ?),
                       closed_at = now()
                 where tenant_id = ? and id = ? and status = 'open'
                """, status, reward, reflection, tenantId, id, tenantId, id);
        return n > 0;
    }

    public List<TrajectoryRow> closedRuns(String tenantId, String goalKey, int limit) {
        return jdbc.query("""
                select * from proc_trajectory
                 where tenant_id = ? and goal_key = ? and status <> 'open'
                 order by closed_at desc nulls last
                 limit ?
                """, TRAJ_MAPPER, tenantId, goalKey, Math.max(1, limit));
    }

    /** 蒸馏入口：最近一段时间里出现过的任务类型。太少的目标不值得算，所以按出现次数过滤。 */
    public List<GoalCount> goalKeys(String tenantId, int days, int limit) {
        return jdbc.query("""
                select goal_key, count(*) as n
                  from proc_trajectory
                 where tenant_id = ? and status <> 'open'
                   and closed_at > now() - (? * interval '1 day')
                 group by goal_key
                 order by n desc, max(closed_at) desc
                 limit ?
                """, (rs, i) -> new GoalCount(rs.getString("goal_key"), rs.getInt("n")),
                tenantId, Math.max(1, days), Math.max(1, limit));
    }

    // ---- L2 策略 ----

    public UUID upsertStrategy(String tenantId, String goalKey, String name, String goal, String whenToUse,
                               String stepsJson, String pitfallsJson, int support,
                               double successRate, double meanReturn, String status) {
        return jdbc.queryForObject("""
                insert into proc_strategy
                  (tenant_id, goal_key, name, goal, when_to_use, steps, pitfalls,
                   support, success_rate, mean_return, status)
                values (?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?, ?)
                on conflict (tenant_id, goal_key) do update set
                  name = excluded.name,
                  goal = excluded.goal,
                  when_to_use = excluded.when_to_use,
                  steps = excluded.steps,
                  pitfalls = excluded.pitfalls,
                  support = excluded.support,
                  success_rate = excluded.success_rate,
                  mean_return = excluded.mean_return,
                  status = excluded.status,
                  updated_at = now()
                returning id
                """, UUID.class, tenantId, goalKey, name, goal, whenToUse, stepsJson, pitfallsJson,
                support, successRate, meanReturn, status);
    }

    public void linkStrategyToRun(String tenantId, UUID trajectoryId, UUID strategyId) {
        jdbc.update("update proc_trajectory set strategy_id = ? where tenant_id = ? and id = ?",
                strategyId, tenantId, trajectoryId);
    }

    public void linkSkillToStrategy(String tenantId, UUID strategyId, UUID skillId) {
        jdbc.update("update proc_strategy set skill_id = ?, updated_at = now() where tenant_id = ? and id = ?",
                skillId, tenantId, strategyId);
    }

    public List<StrategyRow> strategies(String tenantId, int limit) {
        return jdbc.query("""
                select id, goal_key, name, goal, when_to_use, steps::text as steps_json,
                       pitfalls::text as pitfalls_json, support, success_rate, mean_return,
                       status, skill_id, updated_at
                  from proc_strategy
                 where tenant_id = ?
                 order by support desc, success_rate desc, updated_at desc
                 limit ?
                """, STRATEGY_MAPPER, tenantId, Math.max(1, limit));
    }

    public Optional<StrategyRow> strategyByGoal(String tenantId, String goalKey) {
        List<StrategyRow> rows = jdbc.query("""
                select id, goal_key, name, goal, when_to_use, steps::text as steps_json,
                       pitfalls::text as pitfalls_json, support, success_rate, mean_return,
                       status, skill_id, updated_at
                  from proc_strategy
                 where tenant_id = ? and goal_key = ?
                """, STRATEGY_MAPPER, tenantId, goalKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ---- L3 世界模型 ----

    /**
     * 记录一条「动作 → 预期观察」。同一 (subject, action, expectation) 再出现时只加支撑度，
     * 置信度用 Laplace 平滑：1 次成功是 2/3，5 次成功是 6/7，被反例打过的会掉下来。
     */
    public void observeWorld(String tenantId, String subject, String action, String expectation) {
        jdbc.update("""
                insert into proc_world
                  (tenant_id, subject, action, expectation, dedupe_key, confidence, support)
                values (?, ?, ?, ?, md5(? || '|' || ? || '|' || ?), 0.67, 1)
                on conflict (tenant_id, dedupe_key) do update set
                  support = proc_world.support + 1,
                  confidence = (proc_world.support + 2.0)
                             / (proc_world.support + proc_world.contradictions + 3.0),
                  last_seen_at = now()
                """, tenantId, subject, action, expectation, subject, action, expectation);
    }

    /** 失败轨迹里「同样的动作看到了不同结果」即反例：不删这条预期，只降权。 */
    public void contradictWorld(String tenantId, String subject, String action, String expectation) {
        jdbc.update("""
                update proc_world
                   set contradictions = contradictions + 1,
                       confidence = greatest(0.05, (support + 1.0)
                                   / (support + contradictions + 3.0)),
                       last_seen_at = now()
                 where tenant_id = ? and subject = ? and action = ? and expectation <> ?
                """, tenantId, subject, action, expectation);
    }

    public List<WorldRow> world(String tenantId, String subject, int limit) {
        String sql = """
                select subject, action, expectation, confidence, support, contradictions, last_seen_at
                  from proc_world
                 where tenant_id = ?
                """;
        if (subject == null || subject.isBlank()) {
            return jdbc.query(sql + " order by confidence desc, support desc limit ?",
                    WORLD_MAPPER, tenantId, Math.max(1, limit));
        }
        return jdbc.query(sql + " and subject = ? order by confidence desc, support desc limit ?",
                WORLD_MAPPER, tenantId, subject, Math.max(1, limit));
    }

    // ---- Skill ----

    public int nextSkillVersion(String tenantId, String name) {
        Integer v = jdbc.queryForObject(
                "select coalesce(max(version), 0) + 1 from proc_skill where tenant_id = ? and name = ?",
                Integer.class, tenantId, name);
        return v == null ? 1 : v;
    }

    public UUID insertSkill(String tenantId, String name, String title, String description, String body,
                            int version, UUID strategyId, String evidenceJson) {
        return jdbc.queryForObject("""
                insert into proc_skill
                  (tenant_id, name, title, description, body, version, strategy_id, evidence)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                returning id
                """, UUID.class, tenantId, name, title, description, body, version, strategyId, evidenceJson);
    }

    /** 新版本上线时旧版本退休。保留历史版本是为了回答「这份技能是怎么变好的」。 */
    public void retireOlderSkills(String tenantId, String name, int keepVersion) {
        jdbc.update("""
                update proc_skill set status = 'retired', updated_at = now()
                 where tenant_id = ? and name = ? and version < ? and status = 'active'
                """, tenantId, name, keepVersion);
    }

    public List<SkillRow> skills(String tenantId, int limit) {
        return jdbc.query("""
                select id, name, title, description, body, version, evidence::text as evidence_json,
                       status, updated_at
                  from proc_skill
                 where tenant_id = ? and status = 'active'
                 order by updated_at desc
                 limit ?
                """, SKILL_MAPPER, tenantId, Math.max(1, limit));
    }

    public Optional<SkillRow> activeSkill(String tenantId, String name) {
        List<SkillRow> rows = jdbc.query("""
                select id, name, title, description, body, version, evidence::text as evidence_json,
                       status, updated_at
                  from proc_skill
                 where tenant_id = ? and name = ? and status = 'active'
                 order by version desc
                 limit 1
                """, SKILL_MAPPER, tenantId, name);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<SkillRow> latestSkillByStrategy(String tenantId, UUID strategyId) {
        List<SkillRow> rows = jdbc.query("""
                select id, name, title, description, body, version, evidence::text as evidence_json,
                       status, updated_at
                  from proc_skill
                 where tenant_id = ? and strategy_id = ? and status = 'active'
                 order by version desc
                 limit 1
                """, SKILL_MAPPER, tenantId, strategyId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ---- 行映射 ----

    private static final RowMapper<TrajectoryRow> TRAJ_MAPPER = (rs, i) -> new TrajectoryRow(
            rs.getObject("id", UUID.class),
            rs.getString("goal"),
            rs.getString("goal_key"),
            rs.getString("status"),
            nullableDouble(rs, "reward"),
            rs.getInt("step_count"),
            rs.getString("reflection"),
            rs.getString("session_id"),
            rs.getString("project_id"),
            instant(rs, "started_at"),
            instant(rs, "closed_at"));

    private static final RowMapper<StepRow> STEP_MAPPER = (rs, i) -> new StepRow(
            rs.getInt("idx"),
            rs.getString("action"),
            rs.getString("observation"),
            rs.getString("reflection"),
            nullableDouble(rs, "value"),
            nullableDouble(rs, "reflection_weight"),
            nullableDouble(rs, "credit"));

    private static final RowMapper<StrategyRow> STRATEGY_MAPPER = (rs, i) -> new StrategyRow(
            rs.getObject("id", UUID.class),
            rs.getString("goal_key"),
            rs.getString("name"),
            rs.getString("goal"),
            rs.getString("when_to_use"),
            rs.getString("steps_json"),
            rs.getString("pitfalls_json"),
            rs.getInt("support"),
            rs.getDouble("success_rate"),
            rs.getDouble("mean_return"),
            rs.getString("status"),
            rs.getObject("skill_id", UUID.class),
            instant(rs, "updated_at"));

    private static final RowMapper<WorldRow> WORLD_MAPPER = (rs, i) -> new WorldRow(
            rs.getString("subject"),
            rs.getString("action"),
            rs.getString("expectation"),
            rs.getDouble("confidence"),
            rs.getInt("support"),
            rs.getInt("contradictions"),
            instant(rs, "last_seen_at"));

    private static final RowMapper<SkillRow> SKILL_MAPPER = (rs, i) -> new SkillRow(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("body"),
            rs.getInt("version"),
            rs.getString("evidence_json"),
            rs.getString("status"),
            instant(rs, "updated_at"));

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Double nullableDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }

    // ---- 行类型 ----

    public record TrajectoryRow(UUID id, String goal, String goalKey, String status, Double reward,
                                int stepCount, String reflection, String sessionId, String projectId,
                                Instant startedAt, Instant closedAt) {
    }

    public record StepRow(int idx, String action, String observation, String reflection,
                          Double value, Double reflectionWeight, Double credit) {
    }

    public record StrategyRow(UUID id, String goalKey, String name, String goal, String whenToUse,
                              String stepsJson, String pitfallsJson, int support, double successRate,
                              double meanReturn, String status, UUID skillId, Instant updatedAt) {
    }

    public record WorldRow(String subject, String action, String expectation, double confidence,
                           int support, int contradictions, Instant lastSeenAt) {
    }

    public record SkillRow(UUID id, String name, String title, String description, String body,
                           int version, String evidenceJson, String status, Instant updatedAt) {
    }

    public record GoalCount(String goalKey, int runs) {
    }
}
