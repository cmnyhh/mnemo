package com.hs.memory.admin;

/**
 * 控制台自身的配置。
 *
 * @param username     首次启动时用于初始化账号；已初始化过之后不再覆盖（改账号走界面）
 * @param password     首次启动时用于初始化口令；同上
 * @param sessionHours 会话有效期
 * @param cookieMode   auto / true / false —— 走 HTTPS 反代时要加 Secure
 */
public record AdminSettings(String username, String password, int sessionHours, String cookieMode) {

    public static final String DEFAULT_USERNAME = "admin";

    public boolean passwordProvided() {
        return password != null && !password.isBlank();
    }

    public String usernameOrDefault() {
        return username == null || username.isBlank() ? DEFAULT_USERNAME : username.trim();
    }
}
