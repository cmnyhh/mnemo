package com.hs.memory.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /admin/api/** 的会话闸门。
 *
 * <p>三条规则：
 * 一是登录前的两个端点放行（否则没法登录）；
 * 二是读请求只认会话 cookie；
 * 三是写请求还要求 X-CSRF-Token 与会话绑定值一致——SameSite=Strict 已经挡掉大部分，
 * 但「同站不同端口」不算跨站，留着这道更稳。
 *
 * <p>静态页面本身不设防：它只有表单骨架，没有数据。把它也挡住只会让「登录页」无法加载。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AdminFilter extends OncePerRequestFilter {

    public static final String SESSION_ATTR = "adminSession";
    private static final Set<String> OPEN = Set.of("/admin/api/session", "/admin/api/login");

    private final AdminAuthService auth;

    public AdminFilter(AdminAuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if ("/".equals(path) || "/index.html".equals(path) || "/console".equals(path)) {
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Referrer-Policy", "same-origin");
            // 控制台是单文件、无构建产物：升级后必须拿到新页面，不能被浏览器缓存住
            response.setHeader("Cache-Control", "no-store");
            chain.doFilter(request, response);
            return;
        }

        if (!path.startsWith("/admin/api")) {
            chain.doFilter(request, response);
            return;
        }

        response.setHeader("Cache-Control", "no-store");

        if (OPEN.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        Optional<AdminAuthService.Session> session = auth.authenticate(AdminCookies.read(request));
        if (session.isEmpty()) {
            unauthorized(response);
            return;
        }

        String method = request.getMethod();
        boolean mutating = !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
        if (mutating) {
            String csrf = request.getHeader("X-CSRF-Token");
            if (csrf == null || !MessageDigest.isEqual(
                    csrf.getBytes(StandardCharsets.UTF_8),
                    session.get().csrf().getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"CSRF 校验失败，请刷新页面\"}");
                return;
            }
        }

        request.setAttribute(SESSION_ATTR, session.get());
        chain.doFilter(request, response);
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"未登录或会话已过期\",\"login\":true}");
    }
}
