package com.hs.memory.admin;

import com.hs.memory.Settings;
import com.hs.memory.token.TokenService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台的令牌管理。
 *
 * <p>只签发、只撤销，**不提供「查看明文」**——库里存的是 sha256，想再看一眼只能重新签发。
 * 这条限制是故意的：控制台会话 cookie 被盗的代价，不该包括「把客户端令牌全部读走」。
 *
 * <p>所有端点都在 {@link AdminFilter} 后面（要求会话 + 写操作的 CSRF 头），
 * 与 {@code /admin/api} 下其他端点同一把锁。
 */
@RestController
@RequestMapping("/admin/api/tokens")
public class AdminTokenController {

    private final TokenService tokens;
    private final Settings settings;
    private final AdminAuthService auth;
    private final com.hs.memory.obs.AuditService audit;

    public AdminTokenController(TokenService tokens, Settings settings, AdminAuthService auth,
                                com.hs.memory.obs.AuditService audit) {
        this.tokens = tokens;
        this.settings = settings;
        this.auth = auth;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> list() {
        Instant now = Instant.now();
        List<Map<String, Object>> items = tokens.list().stream().map(r -> r.view(now)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("tenant", settings.tenant());
        return out;
    }

    /** 签发。响应里的 {@code token} 是明文，服务端不留副本。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> source = body == null ? Map.of() : body;
        String name = source.get("name") == null ? "" : String.valueOf(source.get("name"));
        Integer expiresInDays = days(source.get("expiresInDays"));
        TokenService.Created created =
                tokens.create(name, expiresInDays, settings.tenant(), auth.username());
        // 明文令牌不进审计：记录里出现一次，就等于多了一处永久副本。
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", created.row().name());
        if (expiresInDays != null) {
            detail.put("expiresInDays", expiresInDays);
        }
        audit.record("token.create", created.row().prefix(), detail);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", created.token());
        out.put("item", created.row().view(Instant.now()));
        out.put("note", "明文只显示这一次，关闭后无法再次查看");
        return out;
    }

    @PostMapping("/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable String id) {
        boolean ok = tokens.revoke(uuid(id));
        audit.record("token.revoke", id, ok ? "ok" : "not-found", Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("note", ok ? "已撤销，持有该令牌的客户端下次请求即 401" : "令牌不存在或已撤销");
        return out;
    }

    /** 清掉已经没用的行（已撤销 / 已过期）。活跃令牌必须先撤销。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        boolean ok = tokens.delete(uuid(id));
        audit.record("token.delete", id, ok ? "ok" : "not-found", Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("note", ok ? "已删除" : "只能删除已撤销或已过期的令牌");
        return out;
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("id 需为 uuid");
        }
    }

    /** 留空 / 不传 = 永不过期；字符串数字也接受（表单控件给的是字符串）。 */
    private static Integer days(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("有效期需为天数");
        }
    }
}
