package com.hs.memory.admin;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;

/** 控制台 cookie 的读写。集中在一处，避免「Secure 加了但 SameSite 忘了」这类不一致。 */
public final class AdminCookies {

    public static final String NAME = "mc_admin";

    private AdminCookies() {
    }

    public static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 生成 Set-Cookie。
     *
     * <p>默认 {@code HttpOnly + SameSite=Strict}：控制台没有跨站场景，Strict 能挡掉
     * 绝大多数 CSRF，剩下的由每个写请求上的 X-CSRF-Token 兜底。
     *
     * @param secureCookie true 时加 Secure；走 HTTPS 反代必须为 true，否则浏览器会拒发
     */
    public static String set(String token, int maxAgeSeconds, boolean secureCookie) {
        StringBuilder sb = new StringBuilder();
        sb.append(NAME).append('=').append(token).append("; Path=/; HttpOnly; SameSite=Strict; Max-Age=")
                .append(maxAgeSeconds);
        if (secureCookie) {
            sb.append("; Secure");
        }
        return sb.toString();
    }

    public static String clear(boolean secureCookie) {
        return set("", 0, secureCookie);
    }

    /**
     * 是否该带 Secure。auto 模式下看请求本身：反代过来的 https 在容器里是 http，
     * 所以还要看 X-Forwarded-Proto，否则「本地能用、线上登不上」。
     */
    public static boolean secure(HttpServletRequest request, String mode) {
        if ("true".equalsIgnoreCase(mode)) {
            return true;
        }
        if ("false".equalsIgnoreCase(mode)) {
            return false;
        }
        if (request.isSecure()) {
            return true;
        }
        String proto = request.getHeader("X-Forwarded-Proto");
        return proto != null && proto.toLowerCase(Locale.ROOT).startsWith("https");
    }

    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
