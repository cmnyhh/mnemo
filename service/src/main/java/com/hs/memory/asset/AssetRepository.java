package com.hs.memory.asset;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AssetRepository {

    private static final RowMapper<AssetRow> MAPPER = (ResultSet rs, int i) -> new AssetRow(
            rs.getObject("id", UUID.class),
            rs.getObject("memory_id", UUID.class),
            rs.getString("kind"),
            rs.getString("mime_type"),
            rs.getLong("bytes"),
            rs.getString("sha256"),
            rs.getString("storage_key"),
            rs.getString("caption"),
            rs.getString("ocr_text"),
            rs.getString("source_name"),
            toInstant(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public AssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(String tenantId, UUID memoryId, String kind, String mime, long bytes,
                       String sha256, String storageKey, String caption, String ocr, String sourceName) {
        List<UUID> ids = jdbc.query("""
                insert into memory_asset (tenant_id, memory_id, kind, mime_type, bytes, sha256,
                                          storage_key, caption, ocr_text, source_name)
                values (?, cast(? as uuid), ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """,
                (rs, i) -> rs.getObject("id", UUID.class),
                tenantId, memoryId == null ? null : memoryId.toString(), kind, mime, bytes,
                sha256, storageKey, caption, ocr, sourceName);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public Optional<AssetRow> find(String tenantId, UUID id) {
        List<AssetRow> rows = jdbc.query("""
                select id, memory_id, kind, mime_type, bytes, sha256, storage_key,
                       caption, ocr_text, source_name, created_at
                  from memory_asset
                 where tenant_id = ? and id = ?
                """, MAPPER, tenantId, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 同一条记忆重复挂同一个文件时复用旧行。上传接口要能被重试：
     * 客户端超时后重发一次，不该变成两条一模一样的附件。
     */
    public Optional<AssetRow> findBySha(String tenantId, String sha256, UUID memoryId) {
        List<AssetRow> rows = jdbc.query("""
                select id, memory_id, kind, mime_type, bytes, sha256, storage_key,
                       caption, ocr_text, source_name, created_at
                  from memory_asset
                 where tenant_id = ? and sha256 = ?
                   and coalesce(memory_id, '00000000-0000-0000-0000-000000000000'::uuid)
                       = coalesce(cast(? as uuid), '00000000-0000-0000-0000-000000000000'::uuid)
                 order by created_at desc
                 limit 1
                """, MAPPER, tenantId, sha256, memoryId == null ? null : memoryId.toString());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 一次取回一批记忆的附件，供召回时拼注入块。 */
    public List<AssetRow> byMemoryIds(String tenantId, List<UUID> memoryIds) {
        if (memoryIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                select id, memory_id, kind, mime_type, bytes, sha256, storage_key,
                       caption, ocr_text, source_name, created_at
                  from memory_asset
                 where tenant_id = ? and memory_id in (
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        for (int i = 0; i < memoryIds.size(); i++) {
            sql.append(i == 0 ? "?": ",?");
            args.add(memoryIds.get(i));
        }
        sql.append(") order by created_at");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public Map<String, Long> countByKind(String tenantId) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("""
                select kind, count(*) as n from memory_asset
                 where tenant_id = ? group by kind order by kind
                """,
                rs -> {
                    out.put(rs.getString("kind"), rs.getLong("n"));
                }, tenantId);
        return out;
    }

    public long count(String tenantId) {
        Long n = jdbc.queryForObject(
                "select count(*) from memory_asset where tenant_id = ?", Long.class, tenantId);
        return n == null ? 0 : n;
    }

    private static Instant toInstant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime odt = rs.getObject(col, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    public record AssetRow(UUID id, UUID memoryId, String kind, String mimeType, long bytes,
                           String sha256, String storageKey, String caption, String ocrText,
                           String sourceName, Instant createdAt) {
    }
}
