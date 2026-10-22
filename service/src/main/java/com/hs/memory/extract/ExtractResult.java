package com.hs.memory.extract;

import java.util.List;

/**
 * 一次抽取的结果。
 *
 * <p>{@code cached=true} 表示命中内容哈希缓存——同一段文本重放不会重复花钱，
 * 也不会因为模型温度产生两份不一致的记忆。
 *
 * <p>{@code admitted=false} 是模型给出的**准入门结论**：这段内容不该进长期记忆。
 * 它和「抽到 0 条」必须分开看——后者是「这段没什么可抽的」，前者是「这段根本不该记」。
 * 两者的处置也不同：前者是抽取偏保守，后者说明上游把提示词、工具回显也当对话发过来了。
 * 准入被拒时 {@code items} 恒为空。
 *
 * <p>{@code summary} 是同一轮的压缩记录，不是抽出来的事实。它和 {@code items} 分开，
 * 因为两者的判据不同：{@code admitted=false} 只否定「沉淀成事实」，不等于「这轮不用记」。
 * 把摘要放进同一次调用的产出，是为了让「有信号」的轮次也只付一次模型的钱。
 *
 * @param reason 拒绝理由（一句话），回给写入方并落日志，便于定位是哪一类杂音
 * @param summary 这一轮聊了什么的记录；模型没给时为 {@code null}
 */
public record ExtractResult(List<ExtractedItem> items, String model, int latencyMs, boolean cached,
                            boolean admitted, String reason, String summary) {

    public static ExtractResult empty(String model) {
        return new ExtractResult(List.of(), model, 0, false, true, null, null);
    }

    public static ExtractResult rejected(String model, int latencyMs, String reason, String summary) {
        return new ExtractResult(List.of(), model, latencyMs, false, false, reason,
                summary == null || summary.isBlank() ? null : summary);
    }

    public int count() {
        return items == null ? 0 : items.size();
    }
}
