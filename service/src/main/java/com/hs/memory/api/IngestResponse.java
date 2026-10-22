package com.hs.memory.api;

import java.util.List;

/**
 * 写入结果。
 *
 * @param extraction 这一轮走了哪条路，排障第一眼就看它：
 *                   {@code structured}（调用方自抽）/ {@code llm-extract}（抽取+摘要）/
 *                   {@code log-summary}（只记摘要）/ {@code summary-fallback}（摘要模型不可用，记原文）/
 *                   {@code episode-fallback}（摘要也不可用，按段硬切）/
 *                   {@code log-skipped}（整轮都是宿主注入的脚手架）/ {@code admission-rejected}（判不该记且无可摘要）
 * @param note 整批层面的说明：为什么一条都没写（准入被拒、全是形状噪音、模型不可用…）。
 *             没有拦改时为 {@code null}。单条层面的说明在 {@link Result#note()}。
 */
public record IngestResponse(
        String extraction,
        int accepted,
        int duplicates,
        List<Result> items,
        String note
) {
    public record Result(
            String id,
            String kind,
            boolean duplicate,
            boolean embedded,
            String note
    ) {
    }
}
