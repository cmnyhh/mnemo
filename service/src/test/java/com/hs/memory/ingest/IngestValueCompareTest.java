package com.hs.memory.ingest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 事实宾语的值比较。
 *
 * <p>这一组断言守的是一个实测过的浪费：库里 22 条被取代的旧事实里有 9 对值完全相同，
 * 只是新记录多写了括号注释（「7800」与「7800（未完成的面板）」），或者标点、全半角有差异。
 * 每判错一对，就有一段历史被白白闭合，而信息一点没增加。
 *
 * <p>反面同样要守住：不做语义合并。「杭州富阳区」与「杭州市富阳区」是人看得出、规则判不出的
 * 同一件事，硬合并会把不同的值并成一条。
 */
class IngestValueCompareTest {

    @Test
    @DisplayName("括号里是补充说明，不参与判等")
    void ignoresParentheticalNotes() {
        assertTrue(IngestService.sameValue("7800", "7800（未完成的面板）"));
        assertTrue(IngestService.sameValue("未启用（开关为灰）", "未启用"));
        assertTrue(IngestService.sameValue("8080 (front)", "8080"));
    }

    @Test
    @DisplayName("全半角与大小写视为同一形状")
    void foldsWidthAndCase() {
        assertTrue(IngestService.sameValue("ＡＢＣ１２３", "abc123"));
        assertTrue(IngestService.sameValue("Qdrant", "qdrant"));
    }

    @Test
    @DisplayName("标点与空白不影响判等")
    void ignoresPunctuationAndSpace() {
        assertTrue(IngestService.sameValue(
                "https://github.com/cmnyhh/qdash-neo-plugins",
                "https://github.com/cmnyhh/qdash-neo-plugins "));
        assertTrue(IngestService.sameValue("卡片白名单、引用消息读不到", "卡片白名单, 引用消息读不到"));
    }

    @Test
    @DisplayName("值真的变了就不算同一条")
    void keepsRealChanges() {
        assertFalse(IngestService.sameValue("7800", "7801"));
        assertFalse(IngestService.sameValue("未启用", "已启用"));
        assertFalse(IngestService.sameValue("1.5.4", "1.5.5"));
    }

    @Test
    @DisplayName("不做语义合并：字面不同就返回 false")
    void doesNotMergeSemantically() {
        assertFalse(IngestService.sameValue("杭州富阳区", "杭州市富阳区"));
        assertFalse(IngestService.sameValue("未勾选", "不在白名单"));
    }

    @Test
    @DisplayName("任一侧为空时放弃判定，宁可走更新也不静默丢弃新值")
    void refusesToCompareBlanks() {
        assertFalse(IngestService.sameValue(null, "7800"));
        assertFalse(IngestService.sameValue("7800", null));
        assertFalse(IngestService.sameValue(null, null));
        assertFalse(IngestService.sameValue("", "7800"));
    }

    @Test
    @DisplayName("归一化后没有内容时返回 null")
    void blankNormalizesToNull() {
        assertNull(IngestService.normalizeValue(null));
        assertNull(IngestService.normalizeValue("   "));
        assertNull(IngestService.normalizeValue("（）"));
    }
}
