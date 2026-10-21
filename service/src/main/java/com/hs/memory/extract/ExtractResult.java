package com.hs.memory.extract;

import java.util.List;

/**
 * 一次抽取的结果。
 *
 * <p>{@code cached=true} 表示命中内容哈希缓存——同一段文本重放不会重复花钱，
 * 也不会因为模型温度产生两份不一致的记忆。
 */
public record ExtractResult(List<ExtractedItem> items, String model, int latencyMs, boolean cached) {

    public static ExtractResult empty(String model) {
        return new ExtractResult(List.of(), model, 0, false);
    }

    public int count() {
        return items == null ? 0 : items.size();
    }
}
