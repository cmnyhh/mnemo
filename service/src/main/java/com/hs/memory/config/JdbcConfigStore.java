package com.hs.memory.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL 实现：一行 JSONB 存覆盖值，另用一张小表存运行时状态。 */
@Repository
public class JdbcConfigStore implements ConfigStore {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcConfigStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Stored load() {
        List<Stored> rows = jdbc.query(
                "select revision, values::text as v, updated_at from app_config where id = true",
                (rs, i) -> new Stored(rs.getLong("revision"), parse(rs.getString("v")), instant(rs.getObject("updated_at", OffsetDateTime.class))));
        return rows.isEmpty() ? new Stored(0, Map.of(), null) : rows.get(0);
    }

    @Override
    public boolean save(Map<String, Object> values, long expectedRevision) {
        String json = write(values);
        if (expectedRevision <= 0) {
            return jdbc.update("""
                    insert into app_config (id, revision, values)
                    values (true, 1, cast(? as jsonb))
                    on conflict (id) do nothing
                    """, json) > 0;
        }
        return jdbc.update("""
                update app_config
                   set values = cast(? as jsonb),
                       revision = revision + 1,
                       updated_at = now()
                 where id = true and revision = ?
                """, json, expectedRevision) > 0;
    }

    @Override
    public String state(String key) {
        List<String> rows = jdbc.query("select value from app_state where key = ?",
                (rs, i) -> rs.getString("value"), key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void putState(String key, String value) {
        jdbc.update("""
                insert into app_state (key, value) values (?, ?)
                on conflict (key) do update set value = excluded.value
                """, key, value);
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = mapper.readValue(json, MAP_TYPE);
            return new LinkedHashMap<>(raw);
        } catch (Exception e) {
            throw new IllegalStateException("app_config.values 不是合法 JSON: " + e.getMessage(), e);
        }
    }

    private String write(Map<String, Object> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("配置无法序列化: " + e.getMessage(), e);
        }
    }

    private static Instant instant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}
