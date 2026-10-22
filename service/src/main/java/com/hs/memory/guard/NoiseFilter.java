package com.hs.memory.guard;

import java.util.regex.Pattern;

/**
 * 形状过滤：把「明显不是记忆」的东西挡在模型之前。
 *
 * <p>为什么要有这一层，而不是全交给模型判断：写进来的一半杂音是**提示词模板本身**
 * （「输出 JSON：」「提取规则：」「&lt;message source_id="8"&gt;」「【参与度参考】…」），
 * 它们出现在抽取模型超时后的兜底路径里——那条路不经过任何模型，按段硬切，来什么写什么。
 * 语义判断救不了它，只有形状能。
 *
 * <p>判据取向：**只认硬信号**。命中即拒绝，不做「打几分」的模糊判别——模糊判别在
 * 边缘样本上会摇摆，而记忆一旦写进去就要靠人工清理。代价是漏掉一些软噪音，
 * 那些交给抽取模型的 {@code admit} 字段。
 *
 * <p>唯一的例外是显式写入：用户说「记住 X」时，X 长什么样都照收（由调用方跳过本过滤器）。
 */
public final class NoiseFilter {

    /** 运行态脚手架块：{@code 【参与度参考】}、{@code 【会话令牌】}、{@code 【当前时间】}… */
    private static final Pattern INJECTED_BLOCK =
            Pattern.compile("(^|\\n)\\s*【[^】\\n]{1,14}】");
    /** 桥接/工具包装出来的伪 XML。 */
    private static final Pattern XML_SCAFFOLD =
            Pattern.compile("<(message|messages|system|tool|tools|result|context|prompt)[\\s>/]",
                    Pattern.CASE_INSENSITIVE);
    /** 消息包装：伪 XML 是 {@code source_id="8"}，而群消息被转成 JSON 时是 {@code "source_id":"33"}。 */
    private static final Pattern SOURCE_ID =
            Pattern.compile("source_id\\s*[\"']?\\s*[:=]\\s*[\"']?\\d");
    private static final Pattern CODE_FENCE = Pattern.compile("```");
    /** 整段就是一个 JSON 数组/对象：是报文，不是记忆。 */
    private static final Pattern JSON_BODY =
            Pattern.compile("^\\s*[\\[{][\\s\\S]*[\\]}]\\s*$");
    /** 提示词模板特征：角色设定、输出格式、抽取规则、few-shot。 */
    private static final Pattern PROMPT_TEMPLATE = Pattern.compile(
            "你是(一个|一名|一位)[^。\\n]{0,24}(器|助手|专家|机器人|角色|审查员)"
            + "|请只输出|只输出\\s*JSON|输出格式\\s*[:：]|输出\\s*JSON\\s*[:：]"
            + "|提取规则\\s*[:：]|few-?shot|格式\\s*[:：]\\s*\\[?\\{");
    /**
     * 同一条判据的英文形态。
     *
     * <p>语言不能成为判据的一部分：同一个宿主换个语言写提示词，不该从「模板」变成「记忆」。
     * 但英文里「你是…」也是日常句式，所以两条都**锚定行首**，并且角色词收窄到
     * assistant / agent / bot 这类明确的自我指称——「You are a good engineer」不算模板。
     *
     * <p>「do not fabricate」这类句子刻意不收：它对正常请求（「请不要编造」）同样成立。
     */
    private static final Pattern PROMPT_TEMPLATE_EN = Pattern.compile(
            "^[\\s>*#-]*(you are|you're)\\s+(a|an)\\s+[^\\n]{0,40}"
            + "\\b(assistant|agent|expert|bot|helper|coding assistant|pair programmer)\\b"
            + "|^[\\s>*#-]*(output|return|respond|reply)\\s+(only\\s+)?(in\\s+)?(strict\\s+)?"
            + "(json|yaml|xml|markdown)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    /** 工具/函数调用的回显。 */
    private static final Pattern TOOL_SCAFFOLD = Pattern.compile(
            "\"tool_calls\"|\"function\"\\s*:|工具调用|tool_call_id|\\bfunction_call\\b");
    /** 孤零零的一行标签（「输出 JSON：」「提取规则：」「对话内容：」）。 */
    private static final Pattern LABEL_LINE =
            Pattern.compile("^\\s*[^\\s:：][^\\n:：]{0,18}[:：]\\s*$");
    /**
     * 运行态状态播报：**通用状态量 + 数值**，不列任何宿主的专有说法。
     *
     * <p>这一条最容易写成产品补丁。「未读数」「可用表情」「唤醒 / 参与度」「首回复节奏」
     * 都是某个宿主自己的叫法：换个场景一条也命中不了，反过来又只覆盖已知措辞。
     * 判据改成「任何场景都有的计数器 / 状态量」——未读、待办、配额、余额、用量、在线、剩余、令牌。
     * 而且必须**带数值**才算状态播报：只出现词不给数的是正常叙述
     * （「mnemo 的评测令牌放在 /root/bench-tokens.json」不该被这条拦下）。
     */
    private static final Pattern RUNTIME_STATE = Pattern.compile(
            "(未读|未处理|待办|待回复|队列|积压|在线|离线|剩余|额度|配额|余额|用量|次数|倒计时"
            + "|会话令牌|token|quota|balance|remaining|online)\\s*[:：=]?\\s*\\d",
            Pattern.CASE_INSENSITIVE);
    /** 随机串 / 编码 / 令牌：长、无空格、混合大小写或含符号。 */
    private static final Pattern OPAQUE_TOKEN = Pattern.compile("[A-Za-z0-9+/_=@#$%^&*.-]{16,}");
    private static final Pattern HEX_OR_B64 = Pattern.compile("^[A-Fa-f0-9]{24,}$|^[A-Za-z0-9_-]{32,}$");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5]");

    private NoiseFilter() {
    }

    /**
     * @return 拒绝原因（可直接写进响应 note）；{@code null} 表示形状上没发现问题。
     */
    public static String reason(String text) {
        return check(text, true);
    }

    /**
     * 只查「脚手架」形状，共五条：伪 XML / 消息包装、提示词模板、工具回显、{@code 【…】} 状态块、
     * 孤立标签行。
     *
     * <p>给 {@code items} 路径用：agent 主动 {@code memory_write} 的条目、调用方自己抽好的条目、
     * 外部管道转发的条目都走这里。这条路刻意保持**最松**——只拦「明显是信封而不是信」的形状：
     * 一段 JSON 配置、一段代码、一把令牌、一条「连接池剩余 50」都是正当内容，把它们当噪声
     * 丢掉是误杀，而误杀比漏一点杂音更难发现。
     *
     * <p>运行态播报、代码块、JSON 报文、随机串属于语义模糊档，只在 {@link #reason} 里查——
     * 那是 turn 兜底切段用的路径，没有模型参与，来什么写什么。
     */
    public static String scaffolding(String text) {
        return check(text, false);
    }

    private static String check(String text, boolean includeAmbiguous) {
        if (text == null || text.isBlank()) {
            return "空内容";
        }
        String t = text.strip();
        if (XML_SCAFFOLD.matcher(t).find() || SOURCE_ID.matcher(t).find()) {
            return "伪 XML / 消息包装";
        }
        if (PROMPT_TEMPLATE.matcher(t).find() || PROMPT_TEMPLATE_EN.matcher(t).find()) {
            return "提示词模板";
        }
        if (TOOL_SCAFFOLD.matcher(t).find()) {
            return "工具调用回显";
        }
        if (INJECTED_BLOCK.matcher(t).find()) {
            return "运行态脚手架块";
        }
        if (LABEL_LINE.matcher(t).matches()) {
            return "孤立标签行";
        }
        if (!includeAmbiguous) {
            return null;
        }
        // 状态播报归到这一档：agent 正当存的「连接池剩余 50」也是「状态量 + 数值」，
        // 只有兜底切段那种「来什么写什么」的路径才需要按它拦。
        if (RUNTIME_STATE.matcher(t).find()) {
            return "运行态状态播报";
        }
        if (CODE_FENCE.matcher(t).find()) {
            return "代码块";
        }
        if (JSON_BODY.matcher(t).matches() && t.length() > 24) {
            return "JSON 报文";
        }
        if (looksOpaque(t)) {
            return "随机串 / 编码";
        }
        return null;
    }

    public static boolean isNoise(String text) {
        return reason(text) != null;
    }

    /**
     * 随机串的判据不是「有没有长串」，而是「整段几乎只有长串，而且那个长串看起来像编码」。
     *
     * <p>「令牌 mn_xxx 已写入 /root/x」是正经记忆；「1asnc@SNCAnujasa」不是。
     * 而 {@code docker-compose.build.yml}、{@code application.yml} 这类全小写带点的文件名
     * 虽然也长，但一眼是标识符不是编码——对它们放行，否则「构建用 docker-compose.build.yml」
     * 这种事实会被误杀。
     */
    private static boolean looksOpaque(String t) {
        var m = OPAQUE_TOKEN.matcher(t);
        int opaqueChars = 0;
        boolean hexLike = false;
        while (m.find()) {
            String run = m.group();
            // 纯数字串（端口、时间戳、ID）不算：它们是事实的一部分
            if (run.chars().allMatch(Character::isDigit)) {
                continue;
            }
            if (!looksRandom(run)) {
                continue;
            }
            opaqueChars += run.length();
            if (HEX_OR_B64.matcher(run).matches()) {
                hexLike = true;
            }
        }
        if (opaqueChars == 0) {
            return false;
        }
        long cjk = CJK.matcher(t).results().count();
        if (hexLike && opaqueChars >= 24) {
            return true;
        }
        return opaqueChars >= 16 && cjk < 6 && opaqueChars * 2 >= t.length();
    }

    /** 像随机串/编码，而不是像标识符。 */
    private static boolean looksRandom(String run) {
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean symbol = false;
        for (int i = 0; i < run.length(); i++) {
            char c = run.charAt(i);
            if (Character.isUpperCase(c)) {
                upper = true;
            } else if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else {
                symbol = true;
            }
        }
        if (upper && lower) {
            return true;                       // 大小写混排：base64 / 随机 token
        }
        if (symbol && (upper || lower) && digit) {
            return true;                       // 带符号且带数字：1asnc@SNCAnujasa
        }
        return HEX_OR_B64.matcher(run).matches();  // 纯 hex 或长 base64url
    }
}
