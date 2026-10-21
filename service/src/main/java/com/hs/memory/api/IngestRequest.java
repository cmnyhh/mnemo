package com.hs.memory.api;

import java.util.List;
import java.util.Map;

/**
 * 写入请求。两种用法：
 * <ul>
 *   <li>插件/工具已经抽好结构化记忆 → 传 {@code items}</li>
 *   <li>只有一轮对话 → 传 {@code turn}，服务端落一条 episode（LLM 抽取是后续的事，v1 不做）</li>
 * </ul>
 */
public record IngestRequest(
        String idempotencyKey,
        String userId,
        String projectId,
        String sessionId,
        List<Item> items,
        Turn turn
) {
    public record Item(
            String kind,
            String scope,
            String tier,
            String narrative,
            Map<String, Object> facts,
            /** 写入时生成的「用户将来会怎么问」。进 BM25 与 embedding，查询时零成本。 */
            List<String> recallHints,
            String subjectKey,
            String predicate,
            String objectValue,
            Double importance,
            Double confidence,
            String trustLevel,
            String projectId,
            String validFrom,
            String sourceKind
    ) {
    }

    /**
     * @param trigger 客户端闸门给出的写入理由：explicit / change / repair。
     *                explicit 表示用户显式要求记住，按手工写入对待。
     */
    public record Turn(String sessionId, Integer index, List<Message> messages, String trigger) {
    }

    public record Message(String role, String content) {
    }
}
