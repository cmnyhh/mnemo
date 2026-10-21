package com.hs.memory.repo;

import java.time.Instant;
import java.util.UUID;

/** 控制台用的记录视图。与召回用的 {@link ItemRow} 分开：这里包含状态与预览。 */
public record AdminRecord(
        UUID id,
        String sessionId,
        Integer turn,
        String kind,
        String scope,
        String tier,
        String status,
        String projectId,
        String narrative,
        int chars,
        Instant validFrom,
        Instant validTo,
        Instant createdAt,
        int accessCount
) {
}
