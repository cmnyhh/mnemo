package com.hs.memory.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hs.memory.api.IngestRequest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 对话日志的拼装规则：谁进日志、进多少、什么时候算「有信号」。 */
class ConversationLogTest {

    private static IngestRequest.Message msg(String role, String content) {
        return new IngestRequest.Message(role, content);
    }

    @Test
    @DisplayName("保留用户问句：问句是「聊了什么」的主体")
    void keepsUserQuestion() {
        String log = ConversationLog.compose(List.of(
                msg("user", "记忆服务的抽取模型配在哪？"),
                msg("assistant", "配在 app_config 里，控制台也能改。")));

        assertEquals("用户：记忆服务的抽取模型配在哪？\n助手：配在 app_config 里，控制台也能改。", log);
    }

    @Test
    @DisplayName("跳过 system / tool / function：那些不是对话，是宿主塞进来的上下文")
    void skipsHostScaffolding() {
        String log = ConversationLog.compose(List.of(
                msg("system", "你是一个乐于助人的助手，输出格式要求如下…"),
                msg("user", "看一下磁盘占用"),
                msg("tool", "{\"total\":\"120G\",\"used\":\"98G\"}"),
                msg("function", "call ok"),
                msg("assistant", "已用 98G，剩 22G。")));

        assertEquals("用户：看一下磁盘占用\n助手：已用 98G，剩 22G。", log);
    }

    @Test
    @DisplayName("整轮只有系统消息 ⇒ 空日志，不写")
    void emptyWhenOnlyScaffolding() {
        assertEquals("", ConversationLog.compose(List.of(
                msg("system", "当前时间 2026-10-22，未读数 3"),
                msg("tool", "{}"))));
        assertEquals("", ConversationLog.compose(List.of()));
        assertEquals("", ConversationLog.compose(null));
    }

    @Test
    @DisplayName("单条消息只压到 2000 字：喂模型的素材要尽量完整")
    void clipsLongMessageAtSummaryLimit() {
        String longReply = "结".repeat(ConversationLog.SUMMARY_MESSAGE_CHARS + 50);
        String log = ConversationLog.compose(List.of(msg("assistant", longReply)));

        assertTrue(log.startsWith("助手：结"));
        assertTrue(log.contains(ConversationLog.ELLIPSIS));
        assertEquals("助手：".length() + ConversationLog.SUMMARY_MESSAGE_CHARS
                + ConversationLog.ELLIPSIS.length(), log.length());
    }

    @Test
    @DisplayName("一轮三千字也照样喂给摘要模型：不能先砍再总结")
    void keepsFullTurnForSummary() {
        List<IngestRequest.Message> many = List.of(
                msg("user", "问".repeat(800)),
                msg("assistant", "答".repeat(800)),
                msg("user", "再".repeat(800)),
                msg("assistant", "续".repeat(800)));

        String log = ConversationLog.compose(many);

        assertTrue(log.length() > ConversationLog.ITEM_CHARS);
        assertFalse(log.contains(ConversationLog.ELLIPSIS));
    }

    @Test
    @DisplayName("回退落库才收紧：原文超长时头尾各留一半")
    void condenseOnlyForFallback() {
        String raw = ConversationLog.compose(List.of(msg("user", "问".repeat(5000))));

        String condensed = ConversationLog.condense(raw);

        assertEquals(ConversationLog.ITEM_CHARS + ConversationLog.ELLIPSIS.length(), condensed.length());
        assertTrue(condensed.contains(ConversationLog.ELLIPSIS));
        assertTrue(condensed.startsWith("用户：问"));
        assertTrue(condensed.endsWith("问"));
    }

    @Test
    @DisplayName("空内容与缺省角色不产生空行")
    void skipsBlankContent() {
        String log = ConversationLog.compose(List.of(
                msg("user", "   "),
                msg(null, "没有角色的消息"),
                msg("assistant", "")));

        assertEquals("用户：没有角色的消息", log);
    }

    @Test
    @DisplayName("信号判定：缺省与 none 都算没信号，只有真 trigger 才花钱抽事实")
    void signalDetection() {
        assertFalse(ConversationLog.hasSignal(null));
        assertFalse(ConversationLog.hasSignal(""));
        assertFalse(ConversationLog.hasSignal("   "));
        assertFalse(ConversationLog.hasSignal("none"));
        assertFalse(ConversationLog.hasSignal("NONE"));

        assertTrue(ConversationLog.hasSignal("explicit"));
        assertTrue(ConversationLog.hasSignal("change"));
        assertTrue(ConversationLog.hasSignal("repair"));
    }
}
