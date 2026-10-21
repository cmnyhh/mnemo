package com.hs.memory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// 分层治理靠定时任务推进（会话冷却、长期未用归档）。单实例部署，不需要分布式调度。
@EnableScheduling
public class MemoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MemoryServiceApplication.class, args);
    }
}
