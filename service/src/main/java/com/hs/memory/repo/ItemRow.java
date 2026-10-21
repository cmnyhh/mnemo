package com.hs.memory.repo;

import java.time.Instant;
import java.util.UUID;

/** 召回与展示需要的字段子集。facts / kw_tokens 不在这一层展开。 */
public record ItemRow(
        UUID id,
        String tenantId,
        String projectId,
        String scope,
        String kind,
        String tier,
        String trustLevel,
        String narrative,
        double importance,
        double confidence,
        int citedCount,
        int correctionCount,
        Instant validFrom,
        Instant validTo,
        Instant createdAt
) {
}
