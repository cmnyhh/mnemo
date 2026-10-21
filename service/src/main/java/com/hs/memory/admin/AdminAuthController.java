package com.hs.memory.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 控制台的会话端点：登录、登出、改账号口令、当前会话。 */
@RestController
@RequestMapping("/admin/api")
public class AdminAuthController {

    private final AdminAuthService auth;
    private final AdminSettings settings;
    private final com.hs.memory.obs.AuditService audit;

    public AdminAuthController(AdminAuthService auth, AdminSettings settings,
                               com.hs.memory.obs.AuditService audit) {
        this.auth = auth;
        this.settings = settings;
        this.audit = audit;
    }

    /**
     * 未登录也返回 200：页面需要知道「控制台是否启用」，否则只能给一个空白登录框。
     * 这里不回任何业务数据，也不回账号名（未登录不该知道账号叫什么）。
     */
    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        Optional<AdminAuthService.Session> session = auth.authenticate(AdminCookies.read(request));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authenticated", session.isPresent());
        out.put("consoleEnabled", auth.enabled());
        out.put("passwordProvided", auth.passwordProvided());
        out.put("sessionHours", settings.sessionHours());
        session.ifPresent(s -> {
            out.put("csrfToken", s.csrf());
            out.put("expiresAt", s.expiresAt().toString());
            out.put("username", auth.username());
        });
        return out;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        String username = body.get("username") == null ? "" : String.valueOf(body.get("username"));
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        AdminAuthService.LoginResult result =
                auth.login(username, password, AdminCookies.clientIp(request));
        // 登录成功和失败都要留痕：只看成功日志，等于对撞库完全失明。
        audit.recordAs(username.isBlank() ? "anonymous" : username.trim(), "console.login", null,
                outcome(result.status()), AdminCookies.clientIp(request),
                Map.of("result", result.status().name()));
        Map<String, Object> out = new LinkedHashMap<>();
        switch (result.status()) {
            case OK -> {
                var session = result.session();
                int maxAge = (int) Duration.between(Instant.now(), session.expiresAt()).toSeconds();
                response.addHeader(HttpHeaders.SET_COOKIE,
                        AdminCookies.set(session.token(), maxAge,
                                AdminCookies.secure(request, settings.cookieMode())));
                out.put("ok", true);
                out.put("csrfToken", session.csrf());
                out.put("expiresAt", session.expiresAt().toString());
                out.put("username", auth.username());
                return ResponseEntity.ok(out);
            }
            case THROTTLED -> {
                out.put("error", result.message());
                out.put("retryAfterSec", result.retryAfterSec());
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, String.valueOf(result.retryAfterSec()))
                        .body(out);
            }
            case DISABLED -> {
                out.put("error", result.message());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(out);
            }
            default -> {
                out.put("error", result.message());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(out);
            }
        }
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        // 先记再作废：会话清掉之后就取不到 actor 了
        audit.record("console.logout", null, Map.of());
        auth.logout(AdminCookies.read(request));
        response.addHeader(HttpHeaders.SET_COOKIE,
                AdminCookies.clear(AdminCookies.secure(request, settings.cookieMode())));
        return Map.of("ok", true);
    }

    /** 审计里的结果字段用短词：控制台按 ok / fail / throttled / disabled 上色。 */
    private static String outcome(AdminAuthService.LoginStatus status) {
        return switch (status) {
            case OK -> "ok";
            case THROTTLED -> "throttled";
            case DISABLED -> "disabled";
            default -> "fail";
        };
    }

    /** 改账号 / 改口令。留空的字段表示不变；两者都空会被拒（多半是误点）。 */
    @PostMapping("/credentials")
    public ResponseEntity<Map<String, Object>> changeCredentials(@RequestBody Map<String, Object> body,
                                                                 HttpServletRequest request,
                                                                 HttpServletResponse response) {
        String oldPassword = body.get("oldPassword") == null ? "" : String.valueOf(body.get("oldPassword"));
        String newUsername = body.get("username") == null ? "" : String.valueOf(body.get("username"));
        String newPassword = body.get("newPassword") == null ? "" : String.valueOf(body.get("newPassword"));
        String actor = auth.username();
        auth.changeCredentials(oldPassword, newUsername, newPassword);
        // 会话已经全清了，actor 只能提前取；这里也不记口令本身，只记「改了哪一项」。
        audit.recordAs(actor, "console.credentials",
                newUsername.isBlank() ? null : newUsername.trim(), "ok",
                AdminCookies.clientIp(request),
                Map.of("passwordChanged", !newPassword.isBlank()));
        // 会话全清了，把浏览器上的那块也抹掉，避免留一个已失效的 cookie 反复 401
        response.addHeader(HttpHeaders.SET_COOKIE,
                AdminCookies.clear(AdminCookies.secure(request, settings.cookieMode())));
        return ResponseEntity.ok(Map.of("ok", true, "relogin", true));
    }
}
