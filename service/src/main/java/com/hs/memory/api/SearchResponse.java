package com.hs.memory.api;

import java.util.List;

public record SearchResponse(
        String query,
        String route,
        boolean abstained,
        int count,
        List<Hit> hits,
        String injection,
        long elapsedMs,
        String degradeReason,
        // 本轮精排状态：off（未配置）/ skipped（触发跳过条件）/ applied / failed
        String rerank
) {
    public record Hit(
            String id,
            String kind,
            String scope,
            String tier,
            String trustLevel,
            String narrative,
            double score,
            double rrf,
            Integer bm25Rank,
            Integer denseRank,
            // 向量原始相似度（余弦）。与 score 不同：它不随名次归一化而改变。
            Double denseScore,
            // 精排给出的相关度；本轮没送精排时为 null。
            Double rerankScore,
            double recency,
            double importance,
            String validFrom,
            String validTo,
            String createdAt,
            // 挂在记忆上的附件。插件据此决定要不要额外发一个 image block；
            // 没有多模态能力的调用方忽略即可。
            List<AssetRef> assets
    ) {
    }

    public record AssetRef(String id, String kind, String mimeType, String caption, String url) {
    }
}
