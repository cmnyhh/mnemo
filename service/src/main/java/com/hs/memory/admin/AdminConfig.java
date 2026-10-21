package com.hs.memory.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdminConfig {

    @Bean
    public AdminSettings adminSettings(
            @Value("${memory.admin.username:admin}") String username,
            @Value("${memory.admin.password:}") String password,
            @Value("${memory.admin.session-hours:12}") int sessionHours,
            @Value("${memory.admin.cookie-secure:auto}") String cookieSecure) {
        return new AdminSettings(username, password,
                Math.max(1, Math.min(sessionHours, 24 * 30)), cookieSecure);
    }
}
