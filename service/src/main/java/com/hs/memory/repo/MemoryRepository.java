package com.hs.memory.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MemoryRepository {

    private static final RowMapper<ItemRow> ITEM_MAPPER = (ResultSet rs, int rowNum) -> new ItemRow(
            rs.getObject("id", UUID.class),
            rs.getString("tenant_id"),
            rs.getString("project_id"),
            rs.getString("scope"),
            rs.getString("kind"),
            rs.getString("tier"),
            rs.getString("trust_level"),
            rs.getString("narrative"),
            rs.getDouble("importance"),
            rs.getDouble("confidence"),
            rs.getInt("cited_count"),
            rs.getInt("correction_count"),
            toInstant(rs, "valid_from"),
            toInstant(rs, "valid_to"),
            toInstant(rs, "created_at"),
            rs.getString("session_id"),
            (Integer) rs.getObject("turn"));

    private final JdbcTemplate jdbc;

    public MemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一条记忆。idem 冲突时不插入、返回空——幂等由数据库唯一约束兜，不靠应用判断。
     */
    public Optional<UUID> insert(NewItem it) {
        String sql = """
                insert into memory_item (
                  id, tenant_id, token_id, user_id, project_id, scope, kind, tier, trust_level,
                  subject_key, predicate, object_value, facts,
                  narrative, recall_hints, search_text, source_kind, session_id, turn,
                  confidence, importance, valid_range, content_hash, idem_key, supersedes_id
                ) values (
                  cast(? as uuid), ?, cast(? as uuid), ?, ?, ?, ?, ?, ?,
                  ?, ?, ?, cast(? as jsonb),
                  ?, string_to_array(?, E'\\x1f'), ?, ?, ?, ?,
                  ?, ?, tstzrange(coalesce(cast(? as timestamptz), now()), null, '[)'), ?, ?, cast(? as uuid)
                )
                on conflict (tenant_id, idem_key) do nothing
                returning id
                """;
        List<UUID> ids = jdbc.query(sql, (rs, i) -> rs.getObject("id", UUID.class),
                it.id().toString(), it.tenantId(),
                it.tokenId() == null ? null : it.tokenId().toString(),
                it.userId(), it.projectId(), it.scope(), it.kind(),
                it.tier(), it.trustLevel(), it.subjectKey(), it.predicate(), it.objectValue(), it.factsJson(),
                it.narrative(), it.recallHints(), it.searchText(), it.sourceKind(), it.sessionId(), it.turn(),
                it.confidence(), it.importance(), it.validFrom(),
                it.contentHash(), it.idemKey(),
                it.supersedesId() == null ? null : it.supersedesId().toString());
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    public Optional<UUID> findIdByKey(String tenantId, String idemKey) {
        List<UUID> ids = jdbc.query(
                "select id from memory_item where tenant_id = ? and idem_key = ?",
                (rs, i) -> rs.getObject("id", UUID.class), tenantId, idemKey);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /**
     * 把同 (tenant, project, subject, predicate) 的 active 事实退休。
     * 返回被退休的那条 id，用来给新记录写 supersedes_id。
     *
     * <p>为什么先退休再插入：排他约束会拦住两条 active 且区间重叠的记录。
     * 这个顺序让「覆盖」成为一次原子替换，而不是先删后插的竞态。
     */
    public Optional<UUID> retireActiveFact(String tenantId, String projectId, String subjectKey, String predicate) {
        if (subjectKey == null || predicate == null) {
            return Optional.empty();
        }
        List<UUID> ids = jdbc.query("""
                select id from memory_item
                 where tenant_id = ? and status = 'active'
                   and subject_key = ? and predicate = ?
                   and coalesce(project_id, '') = coalesce(?, '')
                 for update
                """,
                (rs, i) -> rs.getObject("id", UUID.class), tenantId, subjectKey, predicate, projectId);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        UUID old = ids.get(0);
        jdbc.update("""
                update memory_item
                   set status = 'superseded',
                       valid_range = case
                         when lower(valid_range) >= now() then valid_range
                         else tstzrange(lower(valid_range), now(), '[)')
                       end,
                       updated_at = now()
                 where id = ?
                """, old);
        return Optional.of(old);
    }

    /**
     * BM25 一路。返回顺序即名次。
     *
     * <p>分数语义（1.6.0-dev 实测）：匹配到词项的行是<b>负分</b>，越相关越负；没匹配上的行是 0。
     * 所以外面必须补 {@code where score < 0}——否则任何查询都会把全表以 0 分带回融合层，
     * 再被 RRF 排进注入块。这是这套扩展最容易踩的一处：它不报错，只是悄悄多给结果。
     *
     * <p>排序与 limit 留在内层子查询，保住 Block-Max WAND 的 top-k 剪枝；
     * 过滤放在外层，不影响索引顺序。
     */
    public List<Scored> bm25(String tenantId, String projectId, String queryText,
                             List<String> kinds, List<String> scopes, List<String> tiers,
                             OffsetDateTime asOf, int limit) {
        StringBuilder sql = new StringBuilder("""
                select id, score from (
                  select id, search_text <@> to_bm25query(?, 'idx_mem_bm25') as score
                    from memory_item
                   where tenant_id = ?
                     and status = 'active'
                     and valid_range @> cast(? as timestamptz)
                """);
        List<Object> args = new ArrayList<>();
        args.add(queryText);
        args.add(tenantId);
        args.add(asOf);

        if (projectId != null && !projectId.isBlank()) {
            sql.append(" and project_id = ?");
            args.add(projectId);
        }
        appendIn(sql, args, "kind", kinds);
        appendIn(sql, args, "scope", scopes);
        appendIn(sql, args, "tier", tiers);

        sql.append(" order by search_text <@> to_bm25query(?, 'idx_mem_bm25') limit ?) t where score < 0");
        args.add(queryText);
        args.add(limit);

        return jdbc.query(sql.toString(),
                (rs, i) -> new Scored(rs.getObject("id", UUID.class), rs.getDouble("score")),
                args.toArray());
    }

    private static void appendIn(StringBuilder sql, List<Object> args, String column, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sql.append(" and ").append(column).append(" in (");
        for (int i = 0; i < values.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
            args.add(values.get(i));
        }
        sql.append(')');
    }

    /** 等价于 {@code findByIds(tenantId, ids, null)}：不看时间区间，但仍只取 active 行。 */
    public List<ItemRow> findByIds(String tenantId, List<UUID> ids) {
        return findByIds(tenantId, ids, null);
    }

    /**
     * 按 id 取回用于注入的行。
     *
     * <p><b>这里必须挡掉非 active 与区间不覆盖 {@code as_of} 的行。</b> 向量库的 payload 是写入
     * 那一刻的快照，事实被取代（{@code superseded}）或被删（{@code deleted}）之后并没有回写
     * payload，dense 一路照样会把这些 id 送上来。早先只靠「查不到就当删了」，可 PG 里那条
     * 记录其实还在——于是一条已被新事实取代的旧记录能从向量路重新被注入，直接破掉 I1。
     * 所以过滤放在这里，而不是指望上游每个调用点自己记得。
     */
    public List<ItemRow> findByIds(String tenantId, List<UUID> ids, OffsetDateTime asOf) {
        if (ids.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                select id, tenant_id, project_id, scope, kind, tier, trust_level, narrative,
                       importance, confidence, cited_count, correction_count,
                       lower(valid_range) as valid_from, upper(valid_range) as valid_to, created_at,
                       session_id, turn
                  from memory_item
                 where tenant_id = ? and status = 'active' and id in (
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        for (int i = 0; i < ids.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
            args.add(ids.get(i));
        }
        sql.append(')');
        if (asOf != null) {
            sql.append(" and valid_range @> cast(? as timestamptz)");
            args.add(asOf);
        }
        return jdbc.query(sql.toString(), ITEM_MAPPER, args.toArray());
    }

    /** 一条记忆在会话里的坐标，邻域扩展用它去找「前后几句」。 */
    public record TurnRef(String sessionId, int turn) {
    }

    /**
     * 取锚点前后 {@code radius} 轮的同会话记录。
     *
     * <p>为什么要这一步：一条记忆等于一轮对话，单独给出去常常缺前因后果——「她答应了」
     * 离开上一轮就不可解。LoCoMo 上漏掉的证据里约 40% 落在已召回轮次的 ±3 轮以内，
     * 回填比继续加深检索便宜得多。
     *
     * <p>仍然只回 active、且 {@code valid_range} 覆盖 {@code as_of} 的行：邻域不是法外之地，
     * 被取代或被删的轮次不能借「邻居」身份重新进上下文。
     */
    public List<ItemRow> neighbors(String tenantId, List<TurnRef> anchors, int radius,
                                   Collection<UUID> exclude, OffsetDateTime asOf, int limit) {
        if (anchors.isEmpty() || radius <= 0 || limit <= 0) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                select id, tenant_id, project_id, scope, kind, tier, trust_level, narrative,
                       importance, confidence, cited_count, correction_count,
                       lower(valid_range) as valid_from, upper(valid_range) as valid_to, created_at,
                       session_id, turn
                  from memory_item
                 where tenant_id = ? and status = 'active'
                   and session_id is not null and turn is not null
                   and (
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        for (int i = 0; i < anchors.size(); i++) {
            TurnRef a = anchors.get(i);
            sql.append(i == 0 ? "" : " or ").append("(session_id = ? and turn between ? and ?)");
            args.add(a.sessionId());
            args.add(a.turn() - radius);
            args.add(a.turn() + radius);
        }
        sql.append(')');
        if (exclude != null && !exclude.isEmpty()) {
            sql.append(" and id not in (");
            int i = 0;
            for (UUID id : exclude) {
                sql.append(i++ == 0 ? "?" : ",?");
                args.add(id);
            }
            sql.append(')');
        }
        if (asOf != null) {
            sql.append(" and valid_range @> cast(? as timestamptz)");
            args.add(asOf);
        }
        // 按会话、轮次排序：同一段话的上下文连着读才像对话，乱序会让模型串不起因果。
        sql.append(" order by session_id, turn limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), ITEM_MAPPER, args.toArray());
    }

    /** 被注入的记忆记一次引用，供后续 §7.5 的 citation_gain 使用。 */
    public void touchAccess(String tenantId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder(
                "update memory_item set access_count = access_count + 1, last_access_at = now() where tenant_id = ? and id in (");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        for (int i = 0; i < ids.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
            args.add(ids.get(i));
        }
        sql.append(')');
        jdbc.update(sql.toString(), args.toArray());
    }

    // ---------------------------------------------------------------------
    // 分层：常驻读取与手工改层。改层只动 tier，不碰 valid_range——
    // 分层是「怎么用这条记忆」，不是「这条记忆在什么时候为真」。
    // ---------------------------------------------------------------------

    public List<PinnedRow> listPinned(String tenantId, int limit) {
        return jdbc.query("""
                select id, kind, tier, narrative, importance, trust_level
                  from memory_item
                 where tenant_id = ? and status = 'active' and tier = 'T0'
                 order by importance desc, cited_count desc, updated_at desc
                 limit ?
                """,
                (rs, i) -> new PinnedRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("tier"),
                        rs.getString("narrative"),
                        rs.getDouble("importance"),
                        rs.getString("trust_level")),
                tenantId, limit);
    }

    /**
     * 最近写进来的几条，用于「本轮检索没命中」时的兜底注入。
     *
     * <p>与 {@link #listPinned} 的区别只是筛选条件：这里不要求 tier = T0，也排除 T3（归档层
     * 默认不召回），按写入时间倒序。排序里 importance 优先，是为了让「重要但不新」的记录
     * 仍有机会出现——兜底块的价值在于提醒模型「记忆里有这些」，不在于回答问题。
     */
    public List<PinnedRow> listRecent(String tenantId, String projectId, int limit) {
        if (projectId == null || projectId.isBlank()) {
            return jdbc.query("""
                    select id, kind, tier, narrative, importance, trust_level
                      from memory_item
                     where tenant_id = ? and status = 'active' and tier <> 'T3'
                     order by importance desc, created_at desc
                     limit ?
                    """,
                    (rs, i) -> new PinnedRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("kind"),
                            rs.getString("tier"),
                            rs.getString("narrative"),
                            rs.getDouble("importance"),
                            rs.getString("trust_level")),
                    tenantId, limit);
        }
        return jdbc.query("""
                select id, kind, tier, narrative, importance, trust_level
                  from memory_item
                 where tenant_id = ? and status = 'active' and tier <> 'T3'
                   and (project_id = ? or project_id is null)
                 order by importance desc, created_at desc
                 limit ?
                """,
                (rs, i) -> new PinnedRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("tier"),
                        rs.getString("narrative"),
                        rs.getDouble("importance"),
                        rs.getString("trust_level")),
                tenantId, projectId, limit);
    }

    /** 只用于判断「能不能钉住」这类需要可信度的动作。 */
    public Optional<String> findTrust(String tenantId, UUID id) {
        List<String> found = jdbc.query("""
                select trust_level from memory_item
                 where tenant_id = ? and id = ? and status = 'active'
                """, (rs, i) -> rs.getString(1), tenantId, id);
        return found.stream().findFirst();
    }

    public boolean setTier(String tenantId, UUID id, String tier) {
        int n = jdbc.update("""
                update memory_item set tier = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'active'
                """, tier, tenantId, id);
        return n > 0;
    }

    public boolean softDelete(String tenantId, UUID id) {
        int n = jdbc.update("""
                update memory_item
                   set status = 'deleted',
                       valid_range = case
                         when lower(valid_range) >= now() then valid_range
                         else tstzrange(lower(valid_range), now(), '[)')
                       end,
                       updated_at = now()
                 where tenant_id = ? and id = ? and status <> 'deleted'
                """, tenantId, id);
        return n > 0;
    }

    public Map<String, Object> stats(String tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", jdbc.queryForObject(
                "select count(*) from memory_item where tenant_id = ?", Long.class, tenantId));
        out.put("active", jdbc.queryForObject(
                "select count(*) from memory_item where tenant_id = ? and status = 'active'", Long.class, tenantId));
        List<Map<String, Object>> byKind = jdbc.query(
                "select kind, count(*) as n from memory_item where tenant_id = ? group by 1 order by 1",
                (rs, i) -> Map.of("kind", rs.getString("kind"), "count", rs.getLong("n")), tenantId);
        List<Map<String, Object>> byTier = jdbc.query(
                "select tier, count(*) as n from memory_item where tenant_id = ? and status = 'active' group by 1 order by 1",
                (rs, i) -> Map.of("tier", rs.getString("tier"), "count", rs.getLong("n")), tenantId);
        out.put("byKind", byKind);
        out.put("byTier", byTier);
        out.put("sessions", jdbc.queryForObject("""
                select count(distinct session_id) from memory_item
                 where tenant_id = ? and session_id is not null
                """, Long.class, tenantId));
        out.put("lastWriteAt", jdbc.query(
                "select max(created_at) as ts from memory_item where tenant_id = ?",
                (rs, i) -> rs.getObject("ts", OffsetDateTime.class), tenantId).stream()
                .filter(java.util.Objects::nonNull).findFirst().orElse(null));
        return out;
    }

    public void seedSpace(String spaceId, String provider, String model, String collection, int dims) {
        // 先把别的空间置为不活跃再写自己：uk_space_single_active 只允许一行 true，
        // 顺序反了会直接撞唯一索引。切换模型时旧的 vector 条目也因此不再算「活跃」。
        jdbc.update("""
                update embedding_space set is_active = false
                 where is_active and space_id <> ?
                """, spaceId);
        jdbc.update("""
                insert into embedding_space (space_id, backend, backend_ref, provider, model, native_dim, is_active)
                values (?, 'qdrant', ?, ?, ?, ?, true)
                on conflict (space_id) do update
                   set backend_ref = excluded.backend_ref,
                       provider = excluded.provider,
                       model = excluded.model,
                       native_dim = excluded.native_dim,
                       is_active = true
                """, spaceId, collection, provider, model, dims);
    }

    private static Instant toInstant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime odt = rs.getObject(col, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    // ---------------------------------------------------------------------
    // 控制台读路径。全部只读，且都带 tenant 条件——控制台也不能越过租户边界。
    // ---------------------------------------------------------------------

    /**
     * 分页列出记录（新→旧）。
     *
     * <p>keyset 分页在这里没用：控制台要「第几页」和总数，按 created_at + offset 更直观；
     * 单用户数据量下 offset 的成本可以忽略。真正的大表才需要换。
     */
    public List<AdminRecord> listRecords(String tenantId, String sessionId, String query,
                                         String status, String tier, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                select id, session_id, turn, kind, scope, tier, status, project_id,
                       left(narrative, 300) as narrative, length(narrative) as chars,
                       lower(valid_range) as valid_from, upper(valid_range) as valid_to,
                       created_at, access_count
                  from memory_item
                 where tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendFilters(sql, args, sessionId, query, status, tier);
        sql.append(" order by created_at desc, id desc limit ? offset ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), ADMIN_MAPPER, args.toArray());
    }

    public long countRecords(String tenantId, String sessionId, String query, String status, String tier) {
        StringBuilder sql = new StringBuilder("select count(*) from memory_item where tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendFilters(sql, args, sessionId, query, status, tier);
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    private static void appendFilters(StringBuilder sql, List<Object> args,
                                      String sessionId, String query, String status, String tier) {
        if (sessionId != null && !sessionId.isBlank()) {
            sql.append(" and session_id = ?");
            args.add(sessionId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and status = ?");
            args.add(status);
        }
        if (tier != null && !tier.isBlank()) {
            sql.append(" and tier = ?");
            args.add(tier.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (query != null && !query.isBlank()) {
            sql.append(" and narrative ilike ? escape '\\'");
            args.add("%" + query.trim()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_") + "%");
        }
    }

    public Optional<AdminRecord> record(String tenantId, UUID id) {
        List<AdminRecord> rows = jdbc.query("""
                select id, session_id, turn, kind, scope, tier, status, project_id,
                       narrative, length(narrative) as chars,
                       lower(valid_range) as valid_from, upper(valid_range) as valid_to,
                       created_at, access_count
                  from memory_item
                 where tenant_id = ? and id = ?
                """, ADMIN_MAPPER, tenantId, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 会话清单：控制台的筛选下拉用它，顺带说明「最近还有哪些会话在写」。 */
    public List<Map<String, Object>> sessions(String tenantId, int limit) {
        return jdbc.query("""
                select session_id, count(*) as n, max(created_at) as last_at
                  from memory_item
                 where tenant_id = ? and session_id is not null
                 group by session_id
                 order by 3 desc
                 limit ?
                """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sessionId", rs.getString("session_id"));
                    row.put("count", rs.getLong("n"));
                    OffsetDateTime last = rs.getObject("last_at", OffsetDateTime.class);
                    row.put("lastAt", last == null ? null : last.toInstant().toString());
                    return row;
                }, tenantId, limit);
    }

    /** 重建索引的游标扫描：按 id 升序，只取活着的记录。 */
    public List<ReindexRow> activeForReindex(String tenantId, UUID after, int limit) {
        String sql = after == null
                ? """
                  select id, tenant_id, project_id, kind, tier, session_id, narrative,
                         lower(valid_range) as valid_from
                    from memory_item
                   where tenant_id = ? and status = 'active'
                   order by id
                   limit ?
                  """
                : """
                  select id, tenant_id, project_id, kind, tier, session_id, narrative,
                         lower(valid_range) as valid_from
                    from memory_item
                   where tenant_id = ? and status = 'active' and id > cast(? as uuid)
                   order by id
                   limit ?
                  """;
        Object[] args = after == null
                ? new Object[]{tenantId, limit}
                : new Object[]{tenantId, after.toString(), limit};
        return jdbc.query(sql,
                (rs, i) -> new ReindexRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("tenant_id"),
                        rs.getString("project_id"),
                        rs.getString("kind"),
                        rs.getString("tier"),
                        rs.getString("session_id"),
                        rs.getString("narrative"),
                        toInstant(rs, "valid_from")),
                args);
    }

    public long countActive(String tenantId) {
        Long n = jdbc.queryForObject(
                "select count(*) from memory_item where tenant_id = ? and status = 'active'",
                Long.class, tenantId);
        return n == null ? 0 : n;
    }

    private static final RowMapper<AdminRecord> ADMIN_MAPPER = (ResultSet rs, int rowNum) -> new AdminRecord(
            rs.getObject("id", UUID.class),
            rs.getString("session_id"),
            rs.getObject("turn", Integer.class),
            rs.getString("kind"),
            rs.getString("scope"),
            rs.getString("tier"),
            rs.getString("status"),
            rs.getString("project_id"),
            rs.getString("narrative"),
            rs.getInt("chars"),
            toInstant(rs, "valid_from"),
            toInstant(rs, "valid_to"),
            toInstant(rs, "created_at"),
            rs.getInt("access_count"));

    public record ReindexRow(UUID id, String tenantId, String projectId, String kind, String tier,
                             String sessionId, String narrative, Instant validFrom) {
    }

    public record PinnedRow(UUID id, String kind, String tier, String narrative, double importance,
                            String trustLevel) {
    }


    public static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /** BM25 或向量一路的原始命中。 */
    public record Scored(UUID id, double score) {
    }
}
