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
    @DisplayName("人设与系统提示的各种角色名都跳过：developer / tool_call / observation")
    void skipsEveryScaffoldingRoleName() {
        String log = ConversationLog.compose(List.of(
                msg("developer", "你是一个严格执行输出格式的助手。"),
                msg("system_message", "环境：macOS 15，可用工具：read/write"),
                msg("tool_call", "{\"name\":\"read_file\"}"),
                msg("observation", "file content ..."),
                msg("function_call", "get_time()"),
                msg("user", "构建要多久"),
                msg("assistant", "四十秒左右。")));

        assertEquals("用户：构建要多久\n助手：四十秒左右。", log);
    }

    @Test
    @DisplayName("扮演类角色不是脚手架：宁可多记，也别丢掉真正说过的话")
    void keepsPlayRoles() {
        String log = ConversationLog.compose(List.of(msg("narrator", "门开了，走廊空无一人。")));

        assertTrue(log.contains("门开了，走廊空无一人。"));
    }

    @Test
    @DisplayName("短句也算开口：「构建多久」不该因为只有四个字被当成沉默")
    void countsShortUtterance() {
        assertEquals(4, ConversationLog.userChars(List.of(msg("user", "构建多久"))));
        // 单字与空白仍然不算：那不是「说了什么」，只是按到了发送键
        assertEquals(1, ConversationLog.userChars(List.of(msg("user", "嗯"))));
        assertTrue(ConversationLog.userChars(List.of(msg("user", "嗯"))) < ConversationLog.MIN_USER_CHARS);
        assertEquals(0, ConversationLog.userChars(List.of(msg("user", "   "))));
    }

    @Test
    @DisplayName("人设被塞进 user 消息：只丢这一条，同轮的对话照记")
    void dropsRolePromptButKeepsDialogue() {
        String log = ConversationLog.compose(List.of(
                msg("user", "你是一个群聊黑话学习器。请从下面的聊天记录中提取可能是黑话的候选项，只输出 JSON。"),
                msg("user", "今天群里说的「太刀」是什么意思"),
                msg("assistant", "是太刀鱼的梗。")));

        assertEquals("用户：今天群里说的「太刀」是什么意思\n助手：是太刀鱼的梗。", log);
    }

    @Test
    @DisplayName("同一条判据不分语言：英文人设照样丢，且不误伤英文提问")
    void dropsEnglishRolePrompt() {
        assertEquals("用户：what port", ConversationLog.compose(List.of(
                msg("user", "You are a helpful coding assistant. Output only JSON."),
                msg("user", "what port"))));
        // 「You are right」不是人设
        assertEquals("用户：You are right, 8080 then", ConversationLog.compose(List.of(
                msg("user", "You are right, 8080 then"))));
    }

    @Test
    @DisplayName("用户贴提示词来问问题：那段模板是他讨论的对象，整轮都要留")
    void keepsQuotedPrompt() {
        String log = ConversationLog.compose(List.of(
                msg("user", "这段提示词有什么问题：你是一个群聊黑话学习器，请提取黑话。"),
                msg("assistant", "缺少输出格式约束。")));

        assertTrue(log.startsWith("用户：这段提示词有什么问题"));
        assertTrue(log.contains("助手：缺少输出格式约束。"));
    }

    @Test
    @DisplayName("「你是什么意思」这类日常句不是人设")
    void keepsOrdinarySecondPerson() {
        String log = ConversationLog.compose(List.of(
                msg("user", "你是什么意思，我是说你昨天说的那个端口"),
                msg("assistant", "8080。")));

        assertTrue(log.contains("你是什么意思"));
    }

    @Test
    @DisplayName("问句不是人设：「你是说必须重启吗」命中了人设的每个词，但它在要答案")
    void keepsQuestionWithDirectiveWords() {
        assertEquals("用户：你是说必须重启吗", ConversationLog.compose(List.of(
                msg("user", "你是说必须重启吗"))));
        assertEquals("用户：这些字段必须都填？", ConversationLog.compose(List.of(
                msg("user", "这些字段必须都填？"))));
    }

    @Test
    @DisplayName("整轮只有人设 ⇒ 一条都不进库")
    void emptyWhenOnlyRolePrompt() {
        assertEquals("", ConversationLog.compose(List.of(
                msg("user", "你是长期记忆的准入审查员，你只输出严格 JSON，不输出任何解释。"))));
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
