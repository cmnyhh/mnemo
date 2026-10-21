package com.hs.memory.embed;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 应用层分词器。
 *
 * <p>为什么不在库里分词：pg_jieba 走 CMake 且依赖两层 submodule，构建链在这台机上不可靠
 * （github.com 不通，tarball 不带 submodule）。改为 Java 侧切好、空格分隔写入 search_text，
 * BM25 索引用 text_config='simple'。
 *
 * <p>切分规则：
 * <ul>
 *   <li>拉丁字母 / 数字连成一个 token（保留 . _ - / : 以便 IP、端口、路径不断开）</li>
 *   <li>连续汉字切成二元组（"部署端口" → "部署 署端 端口"）；单字成 run 时保留单字</li>
 * </ul>
 * 二元组是中文无词典分词里性价比最高的一档：召回接近分词，不需要词典，单测可覆盖。
 * 文档与查询走同一个方法，这一点比"用哪种分词器"更重要。
 */
public final class Tokenizer {

    private Tokenizer() {
    }

    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                int j = i;
                while (j < n && isCjk(text.charAt(j))) {
                    j++;
                }
                String run = text.substring(i, j);
                if (run.length() == 1) {
                    out.add(run);
                } else {
                    for (int k = 0; k + 2 <= run.length(); k++) {
                        out.add(run.substring(k, k + 2));
                    }
                }
                i = j;
            } else if (Character.isLetterOrDigit(c)) {
                int j = i;
                while (j < n && isWordChar(text.charAt(j))) {
                    j++;
                }
                String tok = trimEdges(text.substring(i, j)).toLowerCase(Locale.ROOT);
                if (!tok.isEmpty()) {
                    out.add(tok);
                }
                i = j;
            } else {
                i++;
            }
        }
        return out;
    }

    /** narrative + facts 摘要 + recall hints 拼成索引原文。 */
    public static String searchText(String narrative, String extra) {
        StringBuilder sb = new StringBuilder();
        appendTokens(sb, narrative);
        appendTokens(sb, extra);
        return sb.toString().trim();
    }

    private static void appendTokens(StringBuilder sb, String text) {
        for (String t : tokenize(text)) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(t);
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == '/' || c == ':';
    }

    private static String trimEdges(String s) {
        int b = 0;
        int e = s.length();
        while (b < e && !Character.isLetterOrDigit(s.charAt(b))) {
            b++;
        }
        while (e > b && !Character.isLetterOrDigit(s.charAt(e - 1))) {
            e--;
        }
        return s.substring(b, e);
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 基本汉字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF); // 兼容汉字
    }
}
