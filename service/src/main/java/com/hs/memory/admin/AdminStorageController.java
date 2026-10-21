package com.hs.memory.admin;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台「系统 · 存储」用的两个端点。
 *
 * <p>与 {@link AdminConsoleController} 分开：那边是记忆与配置，这边只做磁盘账目与清理，
 * 读一次要扫目录、问 Qdrant，不该混在概览的轮询里。
 */
@RestController
@RequestMapping("/admin/api/storage")
public class AdminStorageController {

    private final StorageService storage;
    private final com.hs.memory.obs.AuditService audit;

    public AdminStorageController(StorageService storage, com.hs.memory.obs.AuditService audit) {
        this.storage = storage;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> snapshot() {
        return storage.snapshot();
    }

    /** 只删不再参与召回的向量副本，记忆条目保留。 */
    @PostMapping("/cleanup")
    public Map<String, Object> cleanup() {
        Map<String, Object> result = storage.cleanupVectors();
        audit.record("storage.cleanup", null, result);
        return result;
    }
}
