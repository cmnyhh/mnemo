package com.hs.memory.ingest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 抽取前的角色过滤。
 *
 * <p>这条判断守的是一个常态化误判：宿主把几千字的系统提示与人设作为一条 system 消息发上来，
 * 抽取模型每次都判「这是提示词模板，不该进记忆」，于是整轮被拒——而真正的那两句对话
 * 从来没进过模型的视野。日志那条路（{@code ConversationLog.compose}）一直有这道过滤，
 * 抽取那条路（{@code joinTurn}）漏了，症状是「库里只有摘要、没有事实，且摘要讲的都是人设」。
 */
class IngestRoleFilterTest {

    @Test
    @DisplayName("系统与工具这类角色不进抽取视野")
    void skipsScaffoldingRoles() {
        assertTrue(IngestService.isScaffoldingRole("system"));
        assertTrue(IngestService.isScaffoldingRole("SYSTEM"));
        assertTrue(IngestService.isScaffoldingRole(" system "));
        assertTrue(IngestService.isScaffoldingRole("developer"));
        assertTrue(IngestService.isScaffoldingRole("tool"));
        assertTrue(IngestService.isScaffoldingRole("function"));
    }

    @Test
    @DisplayName("真正说话的人不能误伤")
    void keepsHumanRoles() {
        assertFalse(IngestService.isScaffoldingRole("user"));
        assertFalse(IngestService.isScaffoldingRole("assistant"));
        assertFalse(IngestService.isScaffoldingRole(null));
        assertFalse(IngestService.isScaffoldingRole(""));
    }
}
