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
        Instant createdAt,
        /** 会话标识与轮次号：用来找「这一条的前后几句」。历史数据可能为空。 */
        String sessionId,
        Integer turn
) {
    /** 能不能参与邻域扩展：没有会话或轮次的记录（跨会话摘要、手工写入）没有邻居。 */
    public boolean hasTurn() {
        return sessionId != null && !sessionId.isBlank() && turn != null;
    }
}
