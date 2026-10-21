package com.hs.memory.extract;

import java.util.List;
import java.util.Map;

/**
 * 抽取出来的一条记忆。
 *
 * <p>{@code narrative} 必须自包含——带主语、带值、带范围。检索只能排序，不能补全：
 * 一条写成「该服务用 8080」的记忆，没有主语，永远召不回来，召回来也没用。
 *
 * <p>{@code recallHints} 是写入时生成「用户将来会怎么问」。实测 81.6% 的提问不含任何标识符，
 * 而写的时候用的词和问的时候用的词几乎从不重合，所以把匹配难度从查询时搬到写入时。
 */
public record ExtractedItem(
        String kind,
        String narrative,
        String subjectKey,
        String predicate,
        String objectValue,
        Map<String, String> facts,
        List<String> entities,
        List<String> recallHints,
        double confidence
) {

    /** 检索文本：narrative + hints。两处都进 BM25，hints 还额外拼进 embedding。 */
    public String retrievalText() {
        if (recallHints == null || recallHints.isEmpty()) {
            return narrative == null ? "" : narrative;
        }
        return narrative + " " + String.join(" ", recallHints);
    }

    /** subject/predicate/object 三者齐全才算结构化事实，才允许参与冲突消解。 */
    public boolean hasTriple() {
        return notBlank(subjectKey) && notBlank(predicate) && notBlank(objectValue);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
