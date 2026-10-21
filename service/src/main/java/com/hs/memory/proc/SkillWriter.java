package com.hs.memory.proc;

import java.util.List;

/**
 * 把策略候选渲染成一份 SKILL.md。
 *
 * <p>为什么是 Markdown 而不是 JSON：技能的读者是 agent 的下一次自己。JSON 需要一层
 * 解析才「看得懂」，而带 frontmatter 的 Markdown 既是人类可审阅的文档，又能被
 * 直接塞进上下文或落成 skill 目录，两种用法都不浪费。
 *
 * <p>证据段不是装饰：一份来源不明的技能比没有技能更危险。写上「跑过几次、成功几次」，
 * 读的人（或模型）才知道该多信它。
 */
public final class SkillWriter {

    private SkillWriter() {
    }

    public static String render(String name, String title, int version, Distiller.Candidate c) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(name).append('\n');
        sb.append("description: \"").append(yaml(c.goal(), 160)).append("\"\n");
        sb.append("version: ").append(version).append('\n');
        sb.append("---\n\n");

        sb.append("# ").append(title == null || title.isBlank() ? c.goal() : title).append("\n\n");
        sb.append("> 由 mnemo 从 ").append(c.support()).append(" 次真实执行中归纳，成功 ")
                .append(percent(c.successRate())).append("。按当时的环境写成，执行前对照现状核对。\n\n");

        sb.append("## 何时使用\n\n").append(c.whenToUse()).append("\n\n");

        sb.append("## 步骤\n\n");
        List<Distiller.Step> steps = c.steps();
        for (Distiller.Step s : steps) {
            sb.append(s.order()).append(". **").append(s.action()).append("**\n");
            if (s.why() != null && !s.why().isBlank()) {
                sb.append("   - 为什么：").append(s.why().trim()).append('\n');
            }
            if (s.expect() != null && !s.expect().isBlank()) {
                sb.append("   - 预期：").append(s.expect().trim()).append('\n');
            }
        }
        sb.append('\n');

        if (!c.pitfalls().isEmpty()) {
            sb.append("## 常见坑\n\n");
            for (String p : c.pitfalls()) {
                sb.append("- ").append(p).append('\n');
            }
            sb.append('\n');
        }

        sb.append("## 证据\n\n");
        sb.append("- 尝试 ").append(c.support()).append(" 次，成功 ").append(Math.round(c.successRate() * c.support()))
                .append(" 次（").append(percent(c.successRate())).append("）\n");
        sb.append("- 平均回报 ").append(String.format("%.2f", c.meanReturn())).append('\n');
        return sb.toString();
    }

    /** frontmatter 的值不能换行、不能带未转义的双引号，否则整份文件解析失败。 */
    private static String yaml(String s, int max) {
        String t = s == null ? "" : Distiller.trunc(s, max);
        return t.replace("\\", "\\\\").replace("\"", "'");
    }

    private static String percent(double v) {
        return Math.round(v * 100) + "%";
    }
}
