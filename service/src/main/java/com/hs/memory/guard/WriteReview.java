package com.hs.memory.guard;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 写入审查：拦住「看起来像偏好、实际是指令」的内容。
 *
 * <p>这是投毒防线里唯一在<b>写入时</b>生效的一道。判断标准很窄——只认命令式语句，
 * 不认「这条内容对不对」。因为系统没有能力判断真伪，硬判只会把正常偏好一起拒掉；
 * 但「把数据发送到某个地址」「忽略以上指令」这类句式没有第二种解释。
 *
 * <p>命中之后的处理是<b>降级而不是拒绝</b>：内容仍然以 episode 留住（万一它真有用），
 * 但它不再是一条会被每轮注入的高权重断言，可信度也一并降到 low。
 */
public final class WriteReview {

    /** 命中即降级。label 只用于给用户一个「为什么」的说法。 */
    private static final List<Rule> RULES = List.of(
            new Rule("忽略既有指令", Pattern.compile(
                    "(忽略|无视|忘记|跳过)[^。；\\n]{0,6}(以上|之前|上述|前面|上面)[^。；\\n]{0,4}(指令|提示|规则|设定|要求)",
                    Pattern.CASE_INSENSITIVE)),
            new Rule("执行命令", Pattern.compile(
                    "(执行|运行|调用)[^。；\\n]{0,6}(命令|脚本|代码|指令|程序)", Pattern.CASE_INSENSITIVE)),
            new Rule("打开外部链接", Pattern.compile(
                    "(打开|访问|点击|点开)[^。；\\n]{0,6}(链接|网址|地址|url|http)", Pattern.CASE_INSENSITIVE)),
            new Rule("外发数据", Pattern.compile(
                    "((以后|今后|每次|所有|一律)[^。；\\n]{0,20})?(发送|转发|上传|提交|抄送)"
                            + "[^。；\\n]{0,12}(到|至|给)[^。；\\n]{0,40}(@|http|\\.com|\\.cn|\\.net|\\.io)",
                    Pattern.CASE_INSENSITIVE)),
            new Rule("长期指令", Pattern.compile(
                    "(以后|今后|从今以后|每次)[^。；\\n]{0,12}(都要|必须|一律|先|自动)[^。；\\n]{0,12}"
                            + "(执行|运行|发送|转发|上传|打开|访问|删除|修改|跳过)",
                    Pattern.CASE_INSENSITIVE)),
            new Rule("索取凭据", Pattern.compile(
                    "(输入|提供|告知|告诉我|输出|打印)[^。；\\n]{0,8}(密码|口令|密钥|token|令牌|验证码|凭证)",
                    Pattern.CASE_INSENSITIVE)),
            new Rule("要求隐瞒", Pattern.compile(
                    "(不要|别|禁止)[^。；\\n]{0,4}(告诉|提及|通知|让)[^。；\\n]{0,6}(用户|他|她|对方)",
                    Pattern.CASE_INSENSITIVE)),
            new Rule("覆盖系统提示", Pattern.compile(
                    "(ignore|disregard|forget)\\s+(all\\s+)?(previous|above|prior)\\s+(instructions?|prompts?)"
                            + "|system\\s*prompt",
                    Pattern.CASE_INSENSITIVE))
    );

    private WriteReview() {
    }

    public record Verdict(boolean hit, String label, String matched) {
        public static Verdict pass() {
            return new Verdict(false, null, null);
        }
    }

    /** 只检查内容本身，不做语义判断——宁可漏一条，也不要把正常偏好误判成攻击。 */
    public static Verdict review(String text) {
        if (text == null || text.isBlank()) {
            return Verdict.pass();
        }
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            if (m.find()) {
                String matched = m.group();
                return new Verdict(true, rule.label(),
                        matched.length() <= 40 ? matched : matched.substring(0, 40) + "…");
            }
        }
        return Verdict.pass();
    }

    /** 只有这两类会因为命中而降级；episode 本来就是「发生过的事」，留着无妨。 */
    public static boolean downgradable(String kind) {
        return "preference".equals(kind) || "fact".equals(kind);
    }

    private record Rule(String label, Pattern pattern) {
    }
}
