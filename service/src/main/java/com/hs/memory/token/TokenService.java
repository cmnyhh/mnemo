package com.hs.memory.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * API 令牌的签发与校验。**唯一来源是 {@code api_token} 表**：可命名、可设过期、
 * 可单独撤销，明文只在签发时出现一次。
 *
 * <p>明文形状 {@code mn_} + 43 位 base64url（32 字节随机数）。库里只存 sha256 十六进制串，
 * 所以「拿到数据库也拿不回令牌」，撤销的代价是重新签发而不是找回。
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    /** 前缀既让人一眼认出这是 mnemo 的令牌，也让密钥扫描类工具能命中。 */
    static final String PREFIX = "mn_";
    static final int SECRET_BYTES = 32;
    /** 明文里保留的可见字符数：mn_ 加 8 位，够区分是哪一把，又不足以还原。 */
    private static final int VISIBLE = PREFIX.length() + 8;

    /** 同一把令牌一分钟内只写一次 last_used_at —— 它是观测字段，不值得每个请求一次 update。 */
    private static final long TOUCH_INTERVAL_MS = 60_000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String COLUMNS =
            "id, name, prefix, tenant_id, created_at, last_used_at, expires_at, revoked_at";

    private static final RowMapper<TokenRow> MAPPER = (rs, i) -> new TokenRow(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("prefix"),
            rs.getString("tenant_id"),
            instant(rs, "created_at"),
            instant(rs, "last_used_at"),
            instant(rs, "expires_at"),
            instant(rs, "revoked_at"));

    private final JdbcTemplate jdbc;
    private final Map<UUID, Long> touched = new ConcurrentHashMap<>();

    public TokenService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一行令牌。永远不含哈希，更不含明文。 */
    public record TokenRow(UUID id, String name, String prefix, String tenant,
                           Instant createdAt, Instant lastUsedAt, Instant expiresAt, Instant revokedAt) {

        public boolean active(Instant now) {
            return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
        }

        public String state(Instant now) {
            if (revokedAt != null) {
                return "revoked";
            }
            if (expiresAt != null && !expiresAt.isAfter(now)) {
                return "expired";
            }
            return "active";
        }

        public Map<String, Object> view(Instant now) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id.toString());
            out.put("name", name);
            out.put("prefix", prefix);
            out.put("tenant", tenant);
            out.put("state", state(now));
            out.put("createdAt", iso(createdAt));
            out.put("lastUsedAt", iso(lastUsedAt));
            out.put("expiresAt", iso(expiresAt));
            out.put("revokedAt", iso(revokedAt));
            return out;
        }

        private static String iso(Instant at) {
            return at == null ? null : at.toString();
        }
    }

    /** 签发结果：{@code token} 是明文，只在这里出现一次。 */
    public record Created(String token, TokenRow row) {
    }

    public Created create(String name, Integer expiresInDays, String tenant, String createdBy) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("令牌名称不能为空");
        }
        if (clean.length() > 64) {
            throw new IllegalArgumentException("令牌名称最多 64 个字符");
        }
        if (expiresInDays != null && (expiresInDays < 1 || expiresInDays > 3650)) {
            throw new IllegalArgumentException("有效期需在 1–3650 天之间，留空表示永不过期");
        }

        Instant expires = expiresInDays == null ? null : Instant.now().plus(Duration.ofDays(expiresInDays));
        String raw = mint();
        String hash = sha256Hex(raw);
        String prefix = prefixOf(raw);
        UUID id = UUID.randomUUID();
        String by = createdBy == null || createdBy.isBlank() ? "console" : createdBy;

        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into api_token (id, name, token_hash, prefix, tenant_id, created_by, expires_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setObject(1, id);
            ps.setString(2, clean);
            ps.setString(3, hash);
            ps.setString(4, prefix);
            ps.setString(5, tenant);
            ps.setString(6, by);
            if (expires == null) {
                ps.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setObject(7, OffsetDateTime.ofInstant(expires, ZoneOffset.UTC));
            }
            return ps;
        });

        TokenRow row = find(id).orElseThrow(
                () -> new IllegalStateException("令牌已写入但读不回来: " + id));
        return new Created(raw, row);
    }

    /**
     * 校验明文令牌。命中且未撤销 / 未过期才返回，顺便刷新 last_used_at。
     * 环境变量令牌不在这里判断——那是 filter 的事，它比这张表更早、也更权威。
     */
    public Optional<TokenRow> verify(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256Hex(raw);
        List<TokenRow> rows = jdbc.query(
                "select " + COLUMNS + " from api_token where token_hash = ?", MAPPER, hash);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        TokenRow row = rows.get(0);
        Instant now = Instant.now();
        if (!row.active(now)) {
            return Optional.empty();
        }
        touch(row.id(), now);
        return Optional.of(row);
    }

    public List<TokenRow> list() {
        return jdbc.query("select " + COLUMNS + " from api_token order by created_at desc", MAPPER);
    }

    public Optional<TokenRow> find(UUID id) {
        List<TokenRow> rows = jdbc.query(
                "select " + COLUMNS + " from api_token where id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean revoke(UUID id) {
        return jdbc.update(
                "update api_token set revoked_at = now() where id = ? and revoked_at is null", id) > 0;
    }

    /** 只允许删「已经没用了」的行：已撤销或已过期。活跃令牌必须先撤销。 */
    public boolean delete(UUID id) {
        return jdbc.update("""
                delete from api_token
                 where id = ?
                   and (revoked_at is not null or (expires_at is not null and expires_at <= now()))
                """, id) > 0;
    }

    // ------------------------------------------------------------------ 纯函数（可单测）

    /** 生成一把新令牌的明文。 */
    public static String mint() {
        byte[] raw = new byte[SECRET_BYTES];
        RANDOM.nextBytes(raw);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** 明文 → 库里存的那个值。带前缀的令牌与裸令牌都按原样哈希，不做归一化。 */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JRE 缺少 SHA-256", e);
        }
    }

    /** 列表里展示的前缀（前 11 位）。太短的输入整体返回，避免 substring 越界。 */
    public static String prefixOf(String token) {
        if (token == null) {
            return "";
        }
        String trimmed = token.trim();
        return trimmed.length() <= VISIBLE ? trimmed : trimmed.substring(0, VISIBLE);
    }

    private void touch(UUID id, Instant now) {
        long ms = now.toEpochMilli();
        Long last = touched.get(id);
        if (last != null && ms - last < TOUCH_INTERVAL_MS) {
            return;
        }
        touched.put(id, ms);
        try {
            jdbc.update("update api_token set last_used_at = now() where id = ?", id);
        } catch (RuntimeException e) {
            // 「最后使用时间」只是运维观测字段，写失败不该让一次正常的召回请求变成 500
            log.debug("更新 last_used_at 失败: {}", e.toString());
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }
}
