package com.hs.memory.extract;

import java.util.List;

/**
 * 一轮对话的摘要。
 *
 * <p>{@code keywords} 不是装饰：摘要天然倾向于「把具体值说得更好读」，端口、路径、版本、命令
 * 正是这样被抹掉的——而它们恰好是日后唯一能检索的锚点。所以除了正文，单独要一遍原词。
 *
 * @param text 摘要正文；模型没给出可用摘要时为 {@code null}
 */
public record Summary(String text, List<String> keywords, String model, int latencyMs) {

    public static Summary empty(String model) {
        return new Summary(null, List.of(), model, 0);
    }

    public boolean usable() {
        return text != null && !text.isBlank();
    }
}
