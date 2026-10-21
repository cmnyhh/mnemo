package com.hs.memory.obs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.memory.Settings;
import com.hs.memory.admin.AdminAuthService;
import com.hs.memory.admin.AdminCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 控制台动作留痕。
 *
 * <p>记什么：改配置、重建索引、清理向量、遗忘与钉住、签发与撤销令牌、登录成功与失败。
 * 不记什么：记忆正文、口令、令牌明文。审计表一旦开始抄正文，它自己就变成了第二份记忆库，
 * 而且是**没有分层、没有退休、没有租户隔离**的那一份。
 *
 * <p>三条硬规则：
 * <ul>
 *   <li>写审计失败绝不能中断被审计的操作——所以所有写路径都吞异常，只留一条 debug 日志；</li>
 *   <li>敏感字段按名字打码（password / token / secret / key），不靠调用方自觉；</li>
 *   <li>保留 180 天，按概率顺手清理，不另起定时任务（单机部署里多一个调度器就多一个静默失败点）。</li>
 * </ul>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** 控制台一页最多看多少条。审计翻页不是主场景，别让它变成全表扫描的入口。 */
    private static final int MAX_LIMIT = 200;
    private static final int RETENTION_DAYS = 180;

    private final JdbcTemplate jdbc;
    private final Settings settings;
    private final AdminAuthService auth;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditService(JdbcTemplate jdbc, Settings settings, AdminAuthService auth) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.auth = auth;
    }

    /** 当前会话是谁就记谁；取不到会话时记 anonymous（登录失败走这条路）。 */
    public void record(String action, String target, Map<String, Object> detail) {
        record(action, target, "ok", detail);
    }

    public void record(String action, String target, String outcome, Map<String, Object> detail) {
        HttpServletRequest request = currentRequest();
        recordAs(actorOf(request), action, target, outcome, ipOf(request), detail);
    }

    /** 显式指定动作主体。登录失败时还没有会话，只能把「试的那个账号」记进 actor。 */
    public void recordAs(String actor, String action, String target, String outcome, String ip,
                         Map<String, Object> detail) {
        try {
            jdbc.update("insert into audit_log (actor, action, target, outcome, ip, tenant_id, detail)"
                            + " values (?, ?, ?, ?, ?, ?, ?::jsonb)",
                    actor == null || actor.isBlank() ? "anonymous" : actor,
                    action, target, outcome == null ? "ok" : outcome, ip,
                    settings.tenant(), json(mask(detail)));
        } catch (Exception e) {
            log.debug("审计写入失败（不影响本次操作）: {}", e.getMessage());
            return;
        }
        trimOccasionally();
    }

    /** 控制台列表：倒序、可按动作过滤。 */
    public Map<String, Object> page(int limit, String action) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        String filter = action == null || action.isBlank() ? null : action.trim();
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            items = jdbc.query(
                    "select id, occurred_at, actor, action, target, outcome, ip, detail::text as detail"
                            + " from audit_log"
                            + " where (?::text is null or action = ?::text)"
                            + " order by occurred_at desc, id desc limit ?",
                    (rs, i) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        Timestamp at = rs.getTimestamp("occurred_at");
                        row.put("id", rs.getLong("id"));
                        row.put("at", at == null ? null : at.toInstant().toString());
                        row.put("actor", rs.getString("actor"));
                        row.put("action", rs.getString("action"));
                        row.put("target", rs.getString("target"));
                        row.put("outcome", rs.getString("outcome"));
                        row.put("ip", rs.getString("ip"));
                        row.put("detail", parse(rs.getString("detail")));
                        return row;
                    },
                    filter, filter, size);
        } catch (Exception e) {
            log.warn("读取审计日志失败: {}", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("actions", actions());
        out.put("retentionDays", RETENTION_DAYS);
        return out;
    }

    private List<String> actions() {
        try {
            return jdbc.queryForList("select distinct action from audit_log order by action", String.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 1/50 的写入顺手清理一次：不值得为它多一个定时任务。 */
    private void trimOccasionally() {
        if (ThreadLocalRandom.current().nextInt(50) != 0) {
            return;
        }
        try {
            jdbc.update("delete from audit_log where occurred_at < now() - (? || ' days')::interval",
                    String.valueOf(RETENTION_DAYS));
        } catch (Exception e) {
            log.debug("审计清理失败: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 上下文

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private String actorOf(HttpServletRequest request) {
        if (request == null) {
            return "system";
        }
        // 会话本身不带用户名（单账号部署），账号名从服务里取；没有会话就是登录失败那条路。
        return auth.authenticate(AdminCookies.read(request)).isPresent()
                ? auth.username()
                : "anonymous";
    }

    private static String ipOf(HttpServletRequest request) {
        return request == null ? null : AdminCookies.clientIp(request);
    }

    // ------------------------------------------------------------------ 打码

    /**
     * 按键名打码。只处理一层嵌套：detail 是我们自己写的，故意的深层结构说明设计错了。
     */
    static Map<String, Object> mask(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        detail.forEach((k, v) -> out.put(k, sensitive(k) ? "***" : v));
        return out;
    }

    private static boolean sensitive(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return k.contains("password") || k.contains("passwd") || k.contains("token")
                || k.contains("secret") || k.contains("apikey") || k.contains("credential");
    }

    private String json(Map<String, Object> detail) {
        try {
            return mapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Object parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(raw, Object.class);
        } catch (Exception e) {
            return raw;
        }
    }

    /** 控制台展示用：动作的中文名。未知动作原样返回，别因为加了一条新动作就显示空白。 */
    public static String label(String action) {
        if (action == null) {
            return "—";
        }
        return switch (action) {
            case "console.login" -> "登录";
            case "console.logout" -> "退出";
            case "console.credentials" -> "改账号口令";
            case "config.update" -> "改配置";
            case "index.reindex" -> "重建索引";
            case "storage.cleanup" -> "清理向量";
            case "record.forget" -> "遗忘记忆";
            case "record.pin" -> "钉住";
            case "record.unpin" -> "取消钉住";
            case "tier.rebalance" -> "分层治理";
            case "token.create" -> "签发令牌";
            case "token.revoke" -> "撤销令牌";
            case "token.delete" -> "删除令牌";
            default -> action;
        };
    }
}
