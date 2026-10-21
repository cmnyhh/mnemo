package com.hs.memory.repo;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 待写入的一条记忆。字段与 memory_item 一一对应，语义判断都在上层。 */
public record NewItem(
        UUID id,
        String tenantId,
        /** 写下这条记录的令牌；无令牌来源（种子数据、脚本）时为 null。仅用于追溯。 */
        UUID tokenId,
        String userId,
        String projectId,
        String scope,
        String kind,
        String tier,
        String trustLevel,
        String subjectKey,
        String predicate,
        String objectValue,
        String factsJson,
        String narrative,
        /** recall hints 用 0x1F 拼接；库里是 text[]，靠 string_to_array 拆。 */
        String recallHints,
        /** 给 embedding 用的纯文本：narrative + recall hints。与 searchText 不同，它不分词。 */
        String retrievalText,
        String searchText,
        String sourceKind,
        String sessionId,
        Integer turn,
        double confidence,
        double importance,
        OffsetDateTime validFrom,
        String contentHash,
        String idemKey,
        UUID supersedesId
) {
}
