package com.hs.memory.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把口语化的问句压成「名词短语」——只在检索已经弃权之后才用。
 *
 * <p>为什么需要它：精排是 cross-encoder，它同时看 query 和记忆原文。query 里每出现一个
 * 在这条记忆里<b>无法被满足</b>的限定词（「放在哪个目录」「是什么」），分数就被扣一点；
 * 攒够几个就跌破 abstain 阈值，整轮弃权、改走兜底块。线上实测（同一事实十种问法）：
 * {@code 备份} 0.881 命中、{@code 备份文件放在哪里} 0 条——差的不是长度，是这些词。
 *
 * <p>与 {@code docs/query-rewrite.md} 的关系：那次证伪的是「扩展」（加词），看的是
 * <b>中位名次</b>（去壳 113→120，无改善）；这里做的是「压缩」（减词），看的是
 * <b>阈值通过率</b>（去壳把 0 条救回 5~6 条）。同一个动作，两个指标，结论相反。
 *
 * <p>刻意只删疑问与套话，<b>不删</b>「文件 / 目录 / 路径」这类容器词：它们在
 * 「配置文件」「数据目录」里是实体的一部分，删掉会把查询变泛。容器词留给精排去扣分，
 * 这里只处理确定无害的部分。
 */
public final class QuerySqueeze {

    private QuerySqueeze() {
    }

    /**
     * 疑问尾巴与请求套话。长串在前——先删「放在哪个目录」再删「哪个」，否则会剩下半截。
     */
    private static final List<String> NOISE = sortLongestFirst(List.of(
            "我只想知道", "我想知道", "我想问一下", "我想问", "帮我查一下", "帮我看看", "帮我看下",
            "帮我找一下", "告诉我一下", "告诉我", "告知我", "告知一下",
            "应该放在", "需要放在", "要放在", "放在哪个", "存在哪个", "保存在哪",
            "放在哪个目录", "放在哪个位置", "放在哪里", "放在哪儿", "放在哪", "放到哪里", "放到哪",
            "存在哪里", "存在哪儿", "存在哪", "在哪里", "在哪儿", "在哪",
            "哪里", "哪儿",
            "应该", "需要", "请把", "请帮我", "帮我", "给我",
            "是哪一个", "是哪个", "是什么", "是多少", "有哪些", "有哪些呢",
            "怎么配置", "怎么配", "怎么办", "怎么用", "怎么样", "怎么弄", "如何配置", "如何",
            "为什么", "是否", "能不能", "可不可以", "有没有", "行不行",
            "哪个目录", "哪个文件", "哪个", "哪些", "多少",
            "一下", "请问", "麻烦"));

    /** 句末语气词，整句只在末尾出现时才有意义。 */
    private static final List<String> TRAILING = List.of("吗", "呢", "吧", "啊", "呀", "嘛");

    /**
     * 压缩查询。没有任何可删成分时原样返回（调用方据此判断「不必重试」）。
     *
     * @param query 原始查询
     * @return 压缩后的短语；可能等于入参，也可能为空（说明整句都是套话）
     */
    public static String squeeze(String query) {
        if (query == null) {
            return "";
        }
        String out = query.trim();
        for (String noise : NOISE) {
            out = out.replace(noise, " ");
        }
        // 尾部语气词只削一次：连削「吗呢吧」会把真的词削掉（「是吗」→「是」）。
        for (String tail : TRAILING) {
            if (out.endsWith(tail) && out.length() > tail.length() + 1) {
                out = out.substring(0, out.length() - tail.length());
                break;
            }
        }
        out = out.replaceAll("[\\s,,。.、;；:：!！?？\"'“”‘’()（）\\[\\]【】]+", " ").trim();
        return out;
    }

    /**
     * 压缩是否真的改变了什么，且结果还够长——两者都满足才值得再检索一次。
     *
     * <p>两个字的门槛是经验值：一个字（「配」「啥」）在中文里几乎召回不到东西，
     * 重试只会多付一次嵌入的钱。
     */
    public static boolean worthRetry(String original, String squeezed) {
        if (squeezed == null || squeezed.isEmpty()) {
            return false;
        }
        String o = original == null ? "" : original.trim();
        if (squeezed.equals(o)) {
            return false;
        }
        return squeezed.replace(" ", "").length() >= 2;
    }

    private static List<String> sortLongestFirst(List<String> patterns) {
        List<String> copy = new ArrayList<>(patterns);
        copy.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(copy);
    }
}
