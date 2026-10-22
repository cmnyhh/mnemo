package com.hs.memory.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hs.memory.api.IngestRequest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 信封剥离：桥接类宿主把运行态说明书和真实消息打包成一条用户消息，
 * 剥不掉就会「整轮被判成提示词模板」或「真实内容被截断切掉」。
 */
class EnvelopeTest {

    /** 取自 QQ 桥接真实报文（已脱敏）：三千字说明书 + 末尾 JSON 快照。 */
    private static final String BRIDGE = """
            【当前时间】2026/10/22 15:23:17（Asia/Shanghai）

            【当前角色】白绫（完整角色卡请调用 qq_get_prompt 查看）

            【会话令牌】152244a8702e4a85b38497856c9494c4（调用二代状态工具时请在参数中带上此令牌）

            【反AI味】你不是客服：不用有求必应，可以装傻、已读乱回、拒绝推荐。

            【表情包策略：像真人一样用，不刷屏】
            - 合适时机：被戳中笑点/槽点、接梗、怼人、赞同、自嘲、安慰、无语、赢了/输了、告别/晚安。
            - 频率：普通闲聊不用每条都配；大约每 3~5 轮来一张就够。
            - 发送：用 qq_send_sticker；一条消息只能是一张表情，不能在同一气泡里附带文字。

            【首回复节奏】先处理已有消息，完整的问题及时回答。

            【此刻状态】未读 2 条；最近一条来自 美丽猫娘：[图片]；你上次发言 0 分钟前

            【唤醒】group:705590372
            原因：anyMessage

            【本次消息快照】
            {"messages":[{"seq":474,"messageId":"276688079","sender":"大肥鱼，，，","userId":"1195465642","text":"没用的鱼才活得久，会用的都被炖了","time":1792653774185},{"seq":475,"messageId":"1530898331","sender":"美丽猫娘","userId":"704692992","text":"[图片]","hasMedia":true,"time":1792653784147}],"recent":[{"seq":471,"sender":"大肥鱼，，，","text":"[引用 🧄：@大肥鱼，，， 来食]收不了，没这功能，不然早把群吃穷了","plain":"收不了，没这功能，不然早把群吃穷了","time":1792653741250},{"seq":472,"sender":"🧄","text":"你这没用的鱼","time":1792653750125}],"unreadCount":2}
            """;

    @Test
    @DisplayName("桥接信封：剥出群聊对话，扔掉角色设定与表情包策略")
    void stripsBridgeEnvelope() {
        String out = Envelope.strip(BRIDGE);

        assertTrue(out.contains("大肥鱼，，，：没用的鱼才活得久，会用的都被炖了"), out);
        assertTrue(out.contains("🧄：你这没用的鱼"), out);
        assertTrue(out.contains("美丽猫娘：[图片]"), out);
        assertFalse(out.contains("表情包策略"), out);
        assertFalse(out.contains("会话令牌"), out);
        assertFalse(out.contains("unreadCount"), out);
        // 引用的原文也在 plain 里重复一次，却只该出现一次
        assertEquals(1, count(out, "收不了，没这功能"), out);
    }

    @Test
    @DisplayName("剥完能被形状过滤放行：这正是之前整轮丢失的原因")
    void strippedTextPassesScaffoldingFilter() {
        assertFalse(com.hs.memory.guard.NoiseFilter.scaffolding(BRIDGE) == null);
        assertEquals(null, com.hs.memory.guard.NoiseFilter.scaffolding(Envelope.strip(BRIDGE)));
    }

    @Test
    @DisplayName("普通文本原样返回：用户自己写的【重要】不是信封")
    void leavesPlainTextAlone() {
        String plain = "【重要】明天上午十点切生产，请提前把备份跑一遍。";
        assertEquals(plain, Envelope.strip(plain));

        String withBrace = "配置是这样：{\"port\":8080,\"host\":\"127.0.0.1\"}";
        assertEquals(withBrace, Envelope.strip(withBrace));
    }

    @Test
    @DisplayName("没有标题的代码块 JSON 不剥：那是正文里的报文")
    void leavesBareJsonAlone() {
        String json = "{\"items\":[{\"text\":\"这是数据不是对话\"}],\"ok\":true}";
        assertEquals(json, Envelope.strip(json));
    }

    @Test
    @DisplayName("只有说明书没有载荷 ⇒ 空串，让上层按「无可记内容」处理")
    void emptyWhenOnlyManual() {
        String manual = """
                【当前时间】2026/10/22 15:23:17
                【当前角色】白绫
                【会话令牌】abc123
                【本次消息快照】{"messages":[],"unreadCount":0}
                """;
        assertEquals("", Envelope.strip(manual));
    }

    @Test
    @DisplayName("有标题没载荷但标题够多：去掉标题行，留下正文")
    void stripsSectionLinesWithoutPayload() {
        String text = """
                【当前时间】2026/10/22 15:23
                【唤醒】group:705590372
                【行动前】先判断群里在聊什么
                群里在说 dsh 的识图插件要不要做，群主想下周开工。
                """;
        String out = Envelope.strip(text);
        assertTrue(out.contains("dsh 的识图插件"), out);
        assertTrue(out.contains("群主想下周开工"), out);
        assertFalse(out.contains("【当前时间】"), out);
    }

    @Test
    @DisplayName("对话日志先剥再截断：真实内容不再被 400 字切掉")
    void composeKeepsDialogueAfterStrip() {
        String log = ConversationLog.compose(List.of(
                new IngestRequest.Message("user", BRIDGE),
                new IngestRequest.Message("assistant", "我这儿也收不了。")));

        assertTrue(log.contains("没用的鱼才活得久"), log);
        assertFalse(log.contains("表情包策略"), log);
        assertTrue(log.length() <= ConversationLog.ITEM_CHARS + 1, log);
    }

    @Test
    @DisplayName("私聊信封：占位名「私聊」换成「对方」，自己说的话标成助手")
    void stripsPrivateChatEnvelope() {
        String priv = """
                【当前时间】2026/10/22 15:27:25（Asia/Shanghai）

                【当前角色】白绫（完整角色卡请调用 qq_get_prompt 查看）

                【会话令牌】be15bf868e768e4fc7b246209476673d

                【唤醒】private:1704040087
                原因：private
                【行动前】先判断：对方说完了吗？

                【本次消息快照】
                {"messages":[{"seq":2,"sender":"私聊","userId":"1704040087","text":"fsasf","isOwner":true}],"recent":[{"seq":1,"sender":"私聊","text":"sda","isOwner":true},{"sender":"我","text":"阁下这是在试键盘，还是在试我。","isSelf":true}],"unreadCount":1}
                """;
        String out = Envelope.strip(priv);

        assertTrue(out.contains("对方：fsasf"), out);
        assertTrue(out.contains("对方：sda"), out);
        assertTrue(out.contains("助手：阁下这是在试键盘"), out);
        assertFalse(out.contains("私聊："), out);
        assertFalse(out.contains("【唤醒】"), out);
    }

    @Test
    @DisplayName("用户侧字数按剥完的真实内容算：三千字说明书里有五个字的「fsasf」也只算五个字")
    void countsRealUserCharsOnly() {
        String priv = """
                【当前时间】2026/10/22 15:27:25
                【当前角色】白绫
                【本次消息快照】
                {"messages":[{"sender":"私聊","text":"fsasf","isOwner":true}],"recent":[{"sender":"我","text":"阁下这是在试键盘，还是在试我。","isSelf":true}],"unreadCount":1}
                """;
        List<IngestRequest.Message> privTurn = List.of(
                new IngestRequest.Message("user", priv),
                new IngestRequest.Message("assistant", "阁下这是在试键盘，还是在试我。"));

        // recent 里自己那两句是「助手说的」，不算用户说了话
        assertEquals(5, ConversationLog.userChars(privTurn));
        assertTrue(ConversationLog.userChars(privTurn) < ConversationLog.MIN_USER_CHARS);

        List<IngestRequest.Message> groupTurn = List.of(
                new IngestRequest.Message("user", BRIDGE),
                new IngestRequest.Message("assistant", "我这儿也收不了。"));
        assertTrue(ConversationLog.userChars(groupTurn) >= ConversationLog.MIN_USER_CHARS);
    }

    // ------------------------------------------------------------------ 其他入口的形状
    //
    // 下面这组用例的用意：信封剥离不能只认某一个宿主的报文。入口有很多（IDE 助手、CLI、
    // 脚本直连、webhook 透传、别的 IM），但归一化之后只有三种形状。形状认得全，换入口就不用改代码。

    /** IDE / CLI 类宿主的典型形状：系统提醒 + 项目上下文 + 末尾的真实提问。 */
    private static final String IDE_TURN = """
            <system-reminder>
            As you answer, keep in mind: never fabricate paths. Available tools: Read, Bash, Edit.
            </system-reminder>

            <context name="repo">
            /srv/memory-service (java, maven)
            ---- src/main/java/com/hs/memory/ingest/IngestService.java ----
            package com.hs.memory.ingest;
            import java.util.List;
            </context>

            <user_query>为什么群聊的记录一直是空的？</user_query>
            """;

    @Test
    @DisplayName("XML 上下文块：只留真实提问，系统提醒与文件内容整块扔掉")
    void stripsXmlContextBlocks() {
        String out = Envelope.strip(IDE_TURN);

        assertTrue(out.contains("为什么群聊的记录一直是空的？"), out);
        assertFalse(out.contains("system-reminder"), out);
        assertFalse(out.contains("Available tools"), out);
        assertFalse(out.contains("package com.hs.memory.ingest"), out);
        assertFalse(out.contains("user_query"), out);
        // 剥完必须过得了形状过滤，否则整轮还是会被当成脚手架丢掉
        assertEquals(null, com.hs.memory.guard.NoiseFilter.scaffolding(out));
    }

    @Test
    @DisplayName("XML 里只有背景没人说话 ⇒ 空串")
    void emptyWhenOnlyContextBlocks() {
        String only = """
                <system-reminder>Today is 2026-10-22. Be concise.</system-reminder>
                <environment><os>macOS</os><shell>zsh</shell></environment>
                """;
        assertEquals("", Envelope.strip(only));
    }

    @Test
    @DisplayName("整段是标准 messages 报文：按 role 转成对话行，不把 JSON 直接喂给模型")
    void stripsMessagesPayload() {
        String payload = """
                [{"role":"user","content":"记忆服务的向量库换成 Qdrant 1.12 了"},
                 {"role":"assistant","content":"已记录：Qdrant v1.12.0，数据卷保留"}]
                """;
        String out = Envelope.strip(payload);

        assertTrue(out.contains("对方：记忆服务的向量库换成 Qdrant 1.12 了"), out);
        assertTrue(out.contains("助手：已记录：Qdrant v1.12.0"), out);
        assertFalse(out.contains("\"role\""), out);
    }

    @Test
    @DisplayName("用户自己粘的 XML 配置不剥：标签不在「上下文」语义表里")
    void leavesUserXmlAlone() {
        String xml = """
                <configuration>
                  <bean id="db" class="com.example.Pool"/>
                </configuration>
                """;
        assertEquals(xml.trim(), Envelope.strip(xml));
    }

    @Test
    @DisplayName("半角昵称也能去前缀：英文宿主的「Alice: 内容」按内容算字数")
    void countsCharsWithAsciiSpeakerPrefix() {
        String line = "Alice: 端口改成 9090 了";
        List<IngestRequest.Message> turn = List.of(new IngestRequest.Message("user", line));

        assertEquals("端口改成 9090 了".length(), ConversationLog.userChars(turn));
    }

    @Test
    @DisplayName("超长消息头尾各留一半：开头的问句和末尾的追问都不能丢")
    void clipsLongMessageFromBothEnds() {
        String longUser = "开头的问题：控制台端口是多少？" + "日志正文".repeat(700) + "末尾追加：顺便看下磁盘。";
        String log = ConversationLog.compose(List.of(
                new IngestRequest.Message("user", longUser),
                new IngestRequest.Message("assistant", "8090。")));

        assertTrue(longUser.length() > ConversationLog.SUMMARY_MESSAGE_CHARS, "样本要足够长");
        assertTrue(log.contains("开头的问题：控制台端口是多少？"), log);
        assertTrue(log.contains("末尾追加：顺便看下磁盘。"), log);
        assertTrue(log.contains(ConversationLog.ELLIPSIS), log);
        // 压完不能超过单条预算太多
        assertTrue(log.length() < ConversationLog.SUMMARY_MESSAGE_CHARS + 40, log);
    }

    private static int count(String text, String needle) {        int n = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return n;
            }
            n += 1;
            from = at + needle.length();
        }
    }
}
