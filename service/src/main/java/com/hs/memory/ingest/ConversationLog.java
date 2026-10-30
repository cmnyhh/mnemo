package com.hs.memory.ingest;

import com.hs.memory.api.IngestRequest;
import com.hs.memory.guard.NoiseFilter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一轮对话按角色拼成原文叙事。
 *
 * <p>这不是最终写进记忆的形态——正常路径是先交给总结模型压成摘要（见
 * {@code IngestService#logTurn}），这里产出的是**摘要失败时的回退**。所以它的取向是
 * 「确定性、可预期、不丢内容」：同一轮对话永远拼出同一个字符串，模型挂了/超时了/没配，
 * 记录照样有一条，而不是这一轮凭空消失。
 *
 * <p>两条规则值得单独说明：
 * <ul>
 *   <li><b>保留用户的问句</b>。问句说明他在关心什么，正是「聊了什么」的主体；只留回答会变成
 *       一本没有目录的答案集。</li>
 *   <li><b>跳过 system / tool / function</b>。那些不是对话，是宿主塞进来的上下文；
 *       把它们写进日志，以后每次检索都会把脚手架的碎片召回注入。</li>
 * </ul>
 */
final class ConversationLog {

    /** 原文回退落库时的整条上限。回退是应急路径，不是全文备份。 */
    static final int ITEM_CHARS = 1200;
    /**
     * 送给摘要模型的单条消息上限。
     *
     * <p>这个值必须比落库上限宽松得多：摘要要「把发生的事情说清楚」，而说清楚的前提是
     * 模型能看到完整经过。早先沿用 400 字，一轮两条消息总共 800 字，模型只能写出一句概括，
     * 于是摘要永远贴着下限。压缩是摘要模型的事，不该在喂给它之前就把素材切掉。
     */
    static final int SUMMARY_MESSAGE_CHARS = 2000;
    /** 送给摘要模型的整轮上限，与 {@code HttpSummarizer.MAX_INPUT_CHARS} 对齐。 */
    static final int SUMMARY_INPUT_CHARS = 8000;
    /**
     * 用户侧至少这么多字才算「这轮有人开口」。
     *
     * <p>按**剥掉信封之后**的长度判：宿主每轮都附几千字运行态说明，按原文长度算，
     * 一句「fsasf」也够长；按真实内容算，它只有几个字符。
     *
     * <p>为什么定得这么低（原为 8）：这条判据的用途只有一个——「这轮到底有没有人开口」，
     * 不是「这句话值不值得记」。定 8 的时候，「构建多久」「端口改了」这种短问句会被当成
     * 沉默处理，而短句恰恰是最常见的对话形态。值不值得沉淀成事实，是抽取模型的判断，
     * 不该在入口用字数替代它。留下的 2 只挡两种：剥完信封是空的，以及单字试键盘。
     */
    static final int MIN_USER_CHARS = 2;

    /** 头尾截断时中间插的省略标记。 */
    static final String ELLIPSIS = "…（中略）…";

    /**
     * 「昵称：内容」的行前缀。半角全角都要认：英文宿主写 {@code Alice: hi}，
     * 中文宿主写 {@code 大肥鱼：…}，两边的冒号不是同一个字符。
     */
    private static final Pattern PREFIX = Pattern.compile("^[^\\s:：/]{1,24}\\s*[:：]\\s*");

    private ConversationLog() {
    }

    /**
     * 客户端是否报了「本轮发生过什么」。
     *
     * <p>没有信号就只记日志、不花钱抽事实。缺省（{@code null}）按无信号处理，
     * 因为老版本插件不带这个字段——它们的每一轮本来都已经被闸门筛过了。
     */
    static boolean hasSignal(String trigger) {
        return trigger != null && !trigger.isBlank() && !"none".equalsIgnoreCase(trigger.trim());
    }

    /**
     * 拼出送给摘要模型的原文。
     *
     * <p>这里刻意**压得松**（单条 2000 / 整轮 8000）：它喂给总结模型，不是直接入库。
     * 摘要能不能把经过说清楚，取决于模型手里有多少素材；在喂进去之前砍到 1200 字，
     * 等于让模型用八百字写一篇本来有三千字的事。
     */
    static String compose(List<IngestRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (IngestRequest.Message m : messages) {
            if (m == null) {
                continue;
            }
            String role = m.role() == null ? "" : m.role().trim();
            // 与抽取那条路共用同一份角色表（IngestService#isScaffoldingRole）：
            // 两边各写一份的时候，这份少了 developer，摘要就把开发态指令当成人说的话记了。
            if (IngestService.isScaffoldingRole(role)) {
                continue;
            }
            String content = m.content() == null ? "" : m.content().trim();
            if (content.isEmpty()) {
                continue;
            }
            // 先剥信封再压缩：宿主会把运行态说明拼在真实消息前面，真实内容在几千字之后，
            // 先压再剥的话，压到的全是说明书。
            content = Envelope.strip(content);
            if (content.isEmpty()) {
                continue;
            }
            // role 说「这是用户说的话」，内容却是一整段人设：宿主把系统提示拼进了 user 消息。
            // 只丢这一条，不丢整轮——别的消息里可能还有真正的对话。
            if (NoiseFilter.isRolePrompt(content)) {
                continue;
            }
            // 控制哨兵不是话（[[END]]、[[SLEEP:180]]…），两条路同一个判据。
            if (NoiseFilter.isControlToken(content)) {
                continue;
            }
            if (content.length() > SUMMARY_MESSAGE_CHARS) {
                content = clip(content, SUMMARY_MESSAGE_CHARS);
            }
            sb.append("assistant".equalsIgnoreCase(role) ? "助手" : "用户").append("：").append(content).append('\n');
        }
        String narrative = sb.toString().trim();
        if (narrative.length() > SUMMARY_INPUT_CHARS) {
            narrative = clip(narrative, SUMMARY_INPUT_CHARS);
        }
        return narrative;
    }

    /**
     * 把 {@link #compose} 的结果压到可以直接落库的长度，用于摘要失败时的原文回退。
     *
     * <p>与 {@link #compose} 分开是因为两者的上限服务不同目的：喂模型要完整，落库要省预算。
     * 同一份文本先宽后紧，只需要在这里收一次。
     */
    static String condense(String narrative) {
        if (narrative == null || narrative.isEmpty()) {
            return "";
        }
        return narrative.length() > ITEM_CHARS ? clip(narrative, ITEM_CHARS) : narrative;
    }

    /**
     * 用户这一侧真正说了多少字——**剥掉信封、去掉角色前缀、剔掉自己说的话**之后。
     *
     * <p>用来回答「这轮到底有没有人说话」。桥接宿主每轮都附三千字说明书，
     * 按原文算人人都是长篇大论；只有在剥完之后还剩下几个字，才算真说了话。
     *
     * <p>载荷里的 {@code recent} 会带上 agent 自己刚发的几句（{@code isSelf}），那些不是
     * 「用户说了什么」。不剔掉的话，私聊里只发了一个「fsasf」，也会被自己那两句回复撑过阈值。
     */
    static int userChars(List<IngestRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (IngestRequest.Message m : messages) {
            if (m == null) {
                continue;
            }
            String role = m.role() == null ? "" : m.role().trim().toLowerCase();
            if (!"user".equals(role)) {
                continue;
            }
            String content = m.content() == null ? "" : m.content().trim();
            if (content.isEmpty()) {
                continue;
            }
            String stripped = Envelope.strip(content);
            // 与 compose 同一条判据：人设不算「用户说了话」，否则三千年人设能把
            // 一个「fsasf」顶过阈值，每轮都在库里留一条噪声。
            if (NoiseFilter.isRolePrompt(stripped)) {
                continue;
            }
            for (String line : stripped.split("\n")) {
                if (line.startsWith(Envelope.SELF_SPEAKER + "：")) {
                    continue;
                }
                total += unprefixed(line).length();
            }
        }
        return total;
    }

    /** 去掉「谁：」前缀。剥信封产出的行都是「昵称：内容」，前缀不是用户说的话。 */
    private static String unprefixed(String line) {
        Matcher m = PREFIX.matcher(line);
        if (m.find()) {
            return line.substring(m.end()).trim();
        }
        return line.trim();
    }

    /**
     * 把超长内容压到 {@code max} 字：**头尾各留一半**。
     *
     * <p>为什么不是从头截：任何宿主注入的上下文都在前面，真实问题在后面——从头截恰好把
     * 唯一有价值的那句切掉。反过来只说尾部也不行：人习惯先说事、再粘一段代码/日志，
     * 问句在开头。两头各留一半是唯一对两种顺序都成立的切法。
     *
     * <p>这条对所有入口生效，不区分是哪个客户端：粘贴长日志、IDE 带上整个文件、
     * 桥接附带群聊快照，都是「长内容里包着一句真话」。
     */
    static String clip(String content, int max) {
        if (content.length() <= max) {
            return content;
        }
        int head = max / 2;
        int tail = max - head;
        return content.substring(0, head) + ELLIPSIS + content.substring(content.length() - tail);
    }
}
