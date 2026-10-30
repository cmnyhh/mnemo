package com.hs.memory.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把「宿主包出来的信封」剥成真实内容。
 *
 * <p>为什么需要这一层：很多宿主不是把用户原话直接投给 agent，而是拼一段运行态说明
 * （当前时间、角色卡、可用工具、项目上下文、检索到的文档…）再把真实消息塞在里面。这类轮次
 * 有两个毛病，任何一个都足以让它在记忆里消失：
 *
 * <ul>
 *   <li>整段长得像提示词模板 / 伪 XML，形状过滤会把它判成脚手架整轮丢掉；</li>
 *   <li>真实内容在几千字之后，按 {@code MESSAGE_CHARS} 截断时早被切掉了。</li>
 * </ul>
 *
 * <p>所以必须在截断之前、判定之前，先把信封剥开：留下「谁说了什么」，扔掉那些「你是谁、你能用什么」
 * 的说明书。
 *
 * <p><b>按形状分层，不按宿主分支。</b>入口有很多种（IM 桥接、IDE 助手、CLI、脚本直连、webhook 透传），
 * 但把它们拼出来的东西归一化之后只有三种形状：
 *
 * <ol>
 *   <li><b>整段是一份对话报文</b>——{@code [{"role":"user","content":"…"}]}，标准 messages 格式、
 *       导出的转录、webhook 原样转发都长这样；</li>
 *   <li><b>XML 上下文块</b>——{@code <system-reminder>…</system-reminder>}、
 *       {@code <context>…</context>}、{@code <user_query>…</user_query>}，IDE 类宿主的主流形状；</li>
 *   <li><b>{@code 【小节】}说明书 + JSON 快照</b>——IM 桥接类宿主的主流形状。</li>
 * </ol>
 *
 * <p>三种都认不出来就原样返回，一个字不动。剥离是**保守**的：只删「明显不是对话」的块，
 * 拿不准时保留。用户自己写一句「【重要】明天上线」、粘一段 XML 配置、贴一个普通 JSON，
 * 都不会被误剥——判据是标签/字段的**语义角色**，不是某个宿主的专有名词。
 *
 * <p>剥完为空，说明这轮真的只有脚手架，交给调用方按「无可记内容」处理。
 */
final class Envelope {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 信封的 {@code 【小节】} 标题：【当前时间】【可用工具】…最长二十字，且在一行内。 */
    private static final Pattern SECTION = Pattern.compile("【[^】\\n]{1,20}】");

    /**
     * 「强信号」上下文标签：宿主专用，任何 agent 宿主都用它装背景，用户自己不会手写。
     *
     * <p>这批**无条件整块删**：整段只剩它们时说明本轮确实只有脚手架。
     */
    private static final List<String> STRONG_TAGS = List.of(
            "system-reminder", "system_prompt", "system_message", "sys_prompt",
            "instructions", "instruction", "rules", "guidelines",
            "environment", "env", "env_info", "runtime",
            "available_tools", "tool_definitions", "tool_result", "function_results",
            "developer", "developer_message", "additional_context", "extra_context",
            "retrieved_documents", "repo_structure", "directory_structure", "repository",
            "codebase", "git_status", "project_context", "workspace",
            "ide_state", "editor_state", "cursor_rules");

    /**
     * 「弱信号」上下文标签：宿主拿它装背景，但**用户也会自己写**——贴一段文件问哪里有 bug、
     * 贴查询结果问为什么少了三条、贴自己的 memory 配置问写法对不对。
     *
     * <p>所以这批不能无条件删。只有整段已经确认是信封（见 {@link #looksLikeEnvelope}）时
     * 才整块删；否则**只脱壳、留内容**——内容进了抽取视野，标签也不会再触发伪 XML 判据。
     *
     * <p>这一档是实测踩出来的：早先把 {@code <file>} 当强信号，用户「帮我看看这个文件」贴进来的
     * 代码整块消失，只剩那句问话；只贴文件不说话时更是剥成空串，整轮一条都不进库。
     */
    private static final List<String> WEAK_TAGS = List.of(
            "context", "system", "file", "files", "open_files", "active_file", "context_files",
            "documents", "document", "attachments", "attachment",
            "result", "results", "memory", "memories", "knowledge",
            "tools", "tool", "functions", "function", "examples", "prompt",
            "snippets", "metadata");

    /**
     * 「对话类」标签：里面装的是人说的话，只去掉标签、留下文本。
     *
     * <p>这些标签去掉之后，{@code <message>}、{@code <user_query>} 之类就不会再触发
     * 形状过滤的「伪 XML」判据，内容却能完整进摘要。
     */
    private static final List<String> DIALOGUE_TAGS = List.of(
            "user_query", "user_message", "user_request", "question", "human",
            "request", "query", "message", "chat");

    /** 一次删掉一个上下文块；{@code \1} 保证开闭标签同名。 */
    private static final Pattern STRONG_BLOCK = block(STRONG_TAGS);
    /** 同上，但只在整段确认是信封时才用。 */
    private static final Pattern WEAK_BLOCK = block(WEAK_TAGS);
    /** 已知标签的开/闭形式，用来清掉嵌套同名标签留下的碎片。 */
    private static final Pattern KNOWN_TAG = tag(concat(STRONG_TAGS, WEAK_TAGS));

    /** 对话类标签只去壳。 */
    private static final Pattern DIALOGUE_TAG = tag(DIALOGUE_TAGS);

    private static Pattern block(List<String> tags) {
        return Pattern.compile(
                "<(" + String.join("|", tags) + ")(\\s[^>]*)?>[\\s\\S]*?</\\1\\s*>",
                Pattern.CASE_INSENSITIVE);
    }

    private static Pattern tag(List<String> tags) {
        return Pattern.compile("</?(?:" + String.join("|", tags) + ")(?:\\s[^>]*)?>",
                Pattern.CASE_INSENSITIVE);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    /** 只看末尾这么长的范围找载荷：载荷总在信的最后一节。 */
    private static final int TAIL_SCAN_CHARS = 60_000;

    /** 一条消息里最多留这么多行对话，超出的是刷屏，不是记忆。 */
    private static final int MAX_LINES = 60;

    /** 整段剥完超过这个长度就截断，后面还有摘要模型和 1200 字上限。 */
    private static final int MAX_CHARS = 2_000;

    /** 载荷里用来表示「谁说的」的字段名。 */
    private static final List<String> SENDER_KEYS =
            List.of("sender", "userName", "nickname", "name", "user", "author");
    /** 载荷里用来表示「说了什么」的字段名。 */
    private static final List<String> TEXT_KEYS =
            List.of("text", "plain", "content", "message", "body", "query");
    /** 载荷里用来表示「这一条是哪个角色」的字段名，标准 messages 格式只有这个。 */
    private static final List<String> ROLE_KEYS = List.of("role", "from", "author_role");

    /** 「自己发的」这类布尔字段的各种叫法。 */
    private static final List<String> SELF_KEYS = List.of("isSelf", "self", "is_self", "fromMe", "mine");

    /**
     * 不是真名字的占位称呼：私聊窗口里所有来信都叫「私聊」，webhook 里叫「user」。
     *
     * <p>直接留着会拼出「私聊：fsasf」「user：xxx」这种句子——摘要模型会把它当成一个人。
     * 这张表只收**通用的占位词**，任何宿主都可能用它，不是某个产品的专有名词。
     */
    private static final List<String> PLACEHOLDER_SENDERS = List.of(
            "私聊", "群聊", "用户", "好友", "联系人", "未知", "匿名", "对方", "某人", "陌生人",
            "user", "unknown", "anonymous", "guest", "peer", "contact", "friend", "private", "group");

    /** 这些 role 值代表「模型自己说的」。 */
    private static final List<String> SELF_ROLES =
            List.of("assistant", "bot", "model", "ai", "system", "agent");

    /** 自己说的话统一标成「助手」，与 {@link ConversationLog} 拼的前缀一致。 */
    static final String SELF_SPEAKER = "助手";
    /** 对方，且拿不到昵称时。 */
    private static final String PEER_SPEAKER = "对方";

    private Envelope() {
    }

    /**
     * @return 剥离信封后的真实内容；不像信封时原样返回（只做 trim）；剥完为空返回空串。
     */
    static String strip(String text) {
        if (text == null) {
            return "";
        }
        String body = text.trim();
        if (body.isEmpty()) {
            return "";
        }
        // 形状一：整段就是一份对话报文。先判它，因为这类文本里通常没有任何【】或 XML 线索。
        if (isWholePayload(body)) {
            JsonNode root = parse(body);
            // 只有「带说话人字段」才算对话报文。一份普通数据（{"items":[{"text":"…"}]}）
            // 同样有 text 字段，但没人说话——那是被分析的对象，不能改写成语录。
            if (root != null && hasSpeaker(root)) {
                String dialogue = dialogueOf(root);
                return dialogue.isEmpty() ? body : abbreviate(dialogue);
            }
            return body;
        }
        // 形状二：XML 上下文块。IDE 类宿主的主流形状，也是形状过滤最容易误杀的一类。
        String out = stripContextBlocks(body);
        // 形状三：【小节】说明书 + JSON 快照。IM 桥接类宿主的主流形状。
        out = stripSections(out);
        return out.trim();
    }

    // ---------------------------------------------------------------- 形状一：对话报文

    /** 整段 trim 后就是一个 JSON 容器，且能解析。 */
    private static boolean isWholePayload(String body) {
        if (body.length() < 12) {
            return false;
        }
        char first = body.charAt(0);
        char last = body.charAt(body.length() - 1);
        if (!((first == '[' && last == ']') || (first == '{' && last == '}'))) {
            return false;
        }
        return parse(body) != null;
    }

    private static JsonNode parse(String text) {
        try {
            JsonNode node = MAPPER.readTree(text);
            return node != null && node.isContainerNode() ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 报文里有没有「谁说的」。
     *
     * <p>这是「对话报文」与「普通 JSON 数据」的分界：两者都可能有 {@code text/content}，
     * 只有前者带 {@code role/sender/from}。没有这一步，用户贴一份接口响应让 agent 分析，
     * 会被改写成一条语录。
     */
    private static boolean hasSpeaker(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (hasSpeaker(child)) {
                    return true;
                }
            }
            return false;
        }
        if (!node.isObject()) {
            return false;
        }
        for (String key : SENDER_KEYS) {
            if (!firstText(node, List.of(key)).isEmpty()) {
                return true;
            }
        }
        for (String key : ROLE_KEYS) {
            if (!firstText(node, List.of(key)).isEmpty()) {
                return true;
            }
        }
        var fields = node.fields();
        while (fields.hasNext()) {
            if (hasSpeaker(fields.next().getValue())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 形状二：XML 上下文块

    /**
     * 删掉上下文类块、脱掉对话类标签。
     *
     * <p>强信号（宿主专用）无条件整块删；弱信号（用户也会自己写的 {@code <file>}、
     * {@code <result>}、{@code <memory>}）只在整段确认是信封时才整块删，否则只脱壳留内容。
     *
     * <p>什么都不命中就原样返回；命中且删空返回空串。
     */
    private static String stripContextBlocks(String body) {
        boolean envelope = looksLikeEnvelope(body);
        String out = STRONG_BLOCK.matcher(body).replaceAll("");
        if (envelope) {
            out = WEAK_BLOCK.matcher(out).replaceAll("");
        }
        // 嵌套同名标签会让非贪婪匹配留下碎片（「…还活着吗</context>」），统一清掉。
        // 弱信号脱壳也走这里：内容留下，标签不留给形状过滤当伪 XML 的证据。
        out = KNOWN_TAG.matcher(out).replaceAll("");
        out = DIALOGUE_TAG.matcher(out).replaceAll("");
        if (!out.equals(body) && out.isBlank()) {
            // 整段都是宿主喂的背景，没有人说话。
            return "";
        }
        return out;
    }

    /**
     * 整段是否已经确认是「宿主拼出来的信封」。
     *
     * <p>判据只有两条：出现宿主专用的强信号标签，或者【小节】标题多到不像人写的。
     * 单看一个 {@code <file>} 不够——那可能只是用户在贴代码。
     */
    private static boolean looksLikeEnvelope(String body) {
        if (STRONG_BLOCK.matcher(body).find()) {
            return true;
        }
        return sectionCount(body) >= 3;
    }

    /** 数到三条就够判断了，不必数完。 */
    private static int sectionCount(String body) {
        Matcher section = SECTION.matcher(body);
        int n = 0;
        while (section.find()) {
            n += 1;
            if (n >= 3) {
                break;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- 形状三：【小节】说明书

    private static String stripSections(String body) {
        int sections = sectionCount(body);
        if (sections == 0) {
            return body;
        }
        JsonNode payload = lastJson(body);
        if (payload != null) {
            String extracted = dialogueOf(payload);
            if (!extracted.isEmpty()) {
                return abbreviate(extracted);
            }
            // 载荷认出来了但里面没有一句对话（纯配置 / 统计）：这轮确实没有可记的话。
            return "";
        }
        // 没有 JSON 载荷，但标题多到不像人写的：去掉标题行，留正文。
        if (sections >= 3) {
            return abbreviate(stripSectionLines(body));
        }
        return body;
    }

    /** 逐行删掉以【小节】开头的说明行。只剩标题的行整行消失，标题后面跟正文的保留正文。 */
    private static String stripSectionLines(String body) {
        StringBuilder sb = new StringBuilder();
        for (String raw : body.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = SECTION.matcher(line);
            if (m.find() && m.start() == 0) {
                String rest = line.substring(m.end()).trim();
                // 「【行动前】先判断：…」这种标题后面连着指令，同样不是对话内容。
                if (rest.isEmpty()) {
                    continue;
                }
                sb.append(rest).append('\n');
                continue;
            }
            sb.append(line).append('\n');
        }
        String out = sb.toString().trim();
        // 剥完只剩一小撮碎句，说明本来就是一段说明书，不是对话。
        return out.length() < 12 ? "" : out;
    }

    /**
     * 找最后一个能解析的 JSON 对象 / 数组。
     *
     * <p>取**最外层**：信封的载荷是一个大对象，里面还嵌着每条消息的小对象；从后往前找第一个
     * 起点会落到最后那条小消息上，只剥出一条。所以扫所有起点，留下 end 最靠后的那个——
     * 外层一定比内层结束得更晚。
     */
    private static JsonNode lastJson(String text) {
        int floor = Math.max(0, text.length() - TAIL_SCAN_CHARS);
        JsonNode best = null;
        int bestEnd = -1;
        for (int start = floor; start < text.length(); start += 1) {
            char c = text.charAt(start);
            if (c != '{' && c != '[') {
                continue;
            }
            int end = matchingClose(text, start);
            if (end < 0 || end <= bestEnd) {
                continue;
            }
            String candidate = text.substring(start, end + 1);
            if (candidate.length() < 12) {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(candidate);
                if (node != null && node.isContainerNode()) {
                    best = node;
                    bestEnd = end;
                }
            } catch (Exception ignored) {
                // 括号平衡但不是合法 JSON（比如正文里的花括号），继续往后找。
            }
        }
        return best;
    }

    /** @return 与 {@code start} 处括号配对的收括号下标，找不到返回 -1。字符串内的括号不计。 */
    private static int matchingClose(String text, int start) {
        char open = text.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i += 1) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth += 1;
            } else if (c == close) {
                depth -= 1;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- 载荷 → 对话行

    /** 递归收集载荷里的「谁说了一句什么」，按出现顺序、去重。 */
    private static String dialogueOf(JsonNode node) {
        if (node == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        collect(node, lines, seen);
        return String.join("\n", lines);
    }

    private static void collect(JsonNode node, List<String> lines, Set<String> seen) {
        if (node == null || lines.size() >= MAX_LINES) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collect(child, lines, seen);
                if (lines.size() >= MAX_LINES) {
                    return;
                }
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        String said = firstText(node, TEXT_KEYS);
        if (!said.isEmpty()) {
            String line = speakerOf(node) + "：" + said;
            if (seen.add(line)) {
                lines.add(line);
            }
        }
        node.fields().forEachRemaining(entry -> collect(entry.getValue(), lines, seen));
    }

    /**
     * 谁说的。
     *
     * <p>四段判断，覆盖三种主流写法：布尔位（{@code isSelf/self/fromMe}）、role（标准 messages
     * 格式没有昵称，只有 {@code role:"assistant"}）、昵称、什么都没有。
     *
     * <p>占位名归到「对方」：它们不是名字，是宿主没拿到昵称时的填充值。
     */
    private static String speakerOf(JsonNode node) {
        for (String key : SELF_KEYS) {
            if (node.path(key).asBoolean(false)) {
                return SELF_SPEAKER;
            }
        }
        String who = firstText(node, SENDER_KEYS);
        if (!who.isEmpty()) {
            if (isSelfLabel(who)) {
                return SELF_SPEAKER;
            }
            return isPlaceholder(who) ? PEER_SPEAKER : who;
        }
        // 没有昵称字段：标准 messages 格式只有 role，靠它判是不是模型自己。
        return isSelfRole(firstText(node, ROLE_KEYS)) ? SELF_SPEAKER : PEER_SPEAKER;
    }

    /** 昵称本身就写着「我」：群聊里 {@code sender:"我"} 就是自己，虽然没给 isSelf。 */
    private static boolean isSelfLabel(String who) {
        String v = who.trim().toLowerCase(Locale.ROOT);
        return "我".equals(v) || "me".equals(v) || "self".equals(v) || "bot".equals(v);
    }

    /** role 字段判角色。昵称缺失时才用，避免 {@code {"role":"user","name":"Alice"}} 丢掉昵称。 */
    private static boolean isSelfRole(String role) {
        if (role.isEmpty()) {
            return false;
        }
        return SELF_ROLES.contains(role.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isPlaceholder(String who) {
        String v = who.trim().toLowerCase(Locale.ROOT);
        for (String p : PLACEHOLDER_SENDERS) {
            if (p.equalsIgnoreCase(v)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取第一个非空文本字段。
     *
     * <p>标准 messages 格式里没有说话人字段，只有 {@code role}——所以这里同时承担
     * 「昵称」与「角色」两个用途：调用方先找昵称，找不到再来找 role，
     * 命中的值交给 {@link #isSelfRole} 判是不是模型自己。
     */
    private static String firstText(JsonNode node, List<String> keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isTextual()) {
                String text = value.asText().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    private static String abbreviate(String text) {
        String out = text.trim();
        if (out.length() > MAX_CHARS) {
            out = out.substring(0, MAX_CHARS) + "…";
        }
        return out;
    }
}
