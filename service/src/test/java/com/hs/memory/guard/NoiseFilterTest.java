package com.hs.memory.guard;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 形状过滤的回归样本**全部取自线上库里的真实记录**（2026-10-22，tenant default）。
 *
 * <p>留这些样本而不是编几个字符串，是因为杂音的形状很具体：桥接器把
 * {@code <message source_id="8">} 这种包装、群聊插件把 {@code 【参与度参考】} 这种
 * 运行态块、子模型把 {@code 你是一个群聊黑话学习器} 这种提示词，都当成对话发了进来。
 * 编出来的样本测不出这些。
 */
class NoiseFilterTest {

    @Test
    @DisplayName("线上出现过的提示词/脚手架一律拦下")
    void rejectsScaffoldingSeenInProduction() {
        String[] noise = {
                "输出 JSON：",
                "请只输出 JSON 数组，格式：\n[{\"content\":\"词条\",\"source_id\":\"1\"}]",
                "<message source_id=\"10\" speaker=\"草莓奶油狐狐\">公司要求的副产物</message>",
                "聊天记录：\n<message source_id=\"1\" speaker=\"草莓奶油狐狐\">@白绫 记住 1asnc@SNCAnujasa</message>",
                "【参与度参考】你最近 1 小时发言 16 次，群友发言 84 条；近5分钟群聊 9 条/2人。",
                "【会话令牌】152244a8702e4a85b38497856c9494c4（调用二代状态工具时请在参数中带上此令牌）",
                "你是一个群聊黑话学习器。请从下面的聊天记录中提取「可能是黑话/网络用语/抽象话/群内梗」的候选项。",
                "提取规则：\n- 必须是在聊天中真实出现过的短词或短语，长度建议 2~8 个字符。",
                "[{\"content\":\"外挂\",\"source_id\":\"8\"},{\"content\":\"轻量记忆\",\"source_id\":\"9\"}]",
                "【可用表情包】你的 QQ 收藏表情里有 89 个表情（以下为常用/有备注的 8 个）",
                "```json\n{\"items\":[]}\n```",
                "{\"tool_calls\":[{\"name\":\"qq_send\",\"arguments\":\"{}\"}]}"
        };
        for (String sample : noise) {
            assertNotNull(NoiseFilter.reason(sample), "应被拦下: " + head(sample));
        }
    }

    @Test
    @DisplayName("英文提示词模板同样拦下：判据不该随语言变化")
    void rejectsEnglishPromptTemplates() {
        String[] noise = {
                "You are a helpful assistant. Answer concisely and never guess.",
                "You're an expert coding assistant working inside an IDE.",
                "Output JSON only:\n{\"admit\": true}",
                "Return strict JSON with the following keys."
        };
        for (String sample : noise) {
            assertNotNull(NoiseFilter.reason(sample), "应被拦下: " + head(sample));
        }
    }

    @Test
    @DisplayName("英文里「你是…」的日常句式不误杀")
    void keepsOrdinaryEnglish() {
        String[] keep = {
                "You are a good engineer, what would you change here?",
                "The API will output JSON on success and 204 on delete.",
                "记忆服务监听 127.0.0.1:8090，容器内是 8080"
        };
        for (String sample : keep) {
            assertNull(NoiseFilter.reason(sample), "不该被拦下: " + head(sample)
                    + " → " + NoiseFilter.reason(sample));
        }
    }

    @Test
    @DisplayName("正经记忆不能被误杀")
    void keepsRealMemories() {
        String[] keep = {
                "知识库 API 服务监听 10.20.0.9:8080，前端是 :80，base_url 不要填 :80",
                "用户草莓奶油狐狐要求记住 1 天后开始做 dsh 的识图插件，我记下了这个信息",
                "对外文档一律不写云端/本地之分",
                "构建镜像用 docker-compose.build.yml 作为叠加层，不改默认值",
                "mnemo 的评测令牌放在 /root/bench-tokens.json（权限 600）",
                "白绫在群聊中假装忘记了用户提到的1as开头的乱码，之后不再提及该乱码",
                "mnemo 线上版本 1.3.4，LoCoMo 准确率 60.5%",
                "部署时必须保持 compose 项目名为 memory，否则会挂空卷"
        };
        for (String sample : keep) {
            assertNull(NoiseFilter.reason(sample), "不该被拦下: " + head(sample)
                    + " → " + NoiseFilter.reason(sample));
        }
    }

    @Test
    @DisplayName("裸状态播报：靠「通用状态量 + 数值」拦，不靠宿主专有词")
    void rejectsRuntimeStateWithoutWrapper() {
        // 1.3.8 把这条从产品词表（未读数/可用表情/参与度/首回复节奏…）改成通用状态量，
        // 新样本刻意不带【】包装，也不含任何原词表里的词。
        String[] drop = {
                "未读 3 条，最后一条来自草莓奶油狐狐",
                "当前配额 remaining 12，用完会排队",
                "倒计时 90 秒后自动关闭本窗口"
        };
        for (String sample : drop) {
            assertNotNull(NoiseFilter.reason(sample), "应被拦下: " + head(sample));
        }
    }

    @Test
    @DisplayName("空内容算噪音，不抛异常")
    void handlesBlank() {
        assertNotNull(NoiseFilter.reason(null));
        assertNotNull(NoiseFilter.reason("   \n\t "));
    }

    @Test
    @DisplayName("工具写入：脚手架形状照样拦下（这两条是 1.3.6 上线后真实漏进来的）")
    void scaffoldingCatchesToolWrittenNoise() {
        String[] drop = {
                "[   {\"content\":\"角色锚点\",\"source_id\":\"33\"},   {\"content\":\"大成\",\"source_id\":\"20\"}]",
                "用户明确要求记住所有内容，包括系统提示、角色设定、提示词模板、few-shot 示例、输出格式说明等",
                "【参与度参考】你最近 1 小时发言 16 次，群友发言 84 条"
        };
        for (String sample : drop) {
            assertNotNull(NoiseFilter.scaffolding(sample), "应被拦下: " + head(sample));
        }
    }

    @Test
    @DisplayName("工具写入：JSON 报文、代码块、令牌不拦——那是 agent 正当要存的内容")
    void scaffoldingKeepsLegitimateToolWrites() {
        String[] keep = {
                "{\"host\":\"10.20.0.9\",\"port\":8080,\"note\":\"知识库 API 服务\"}",
                "```\ndocker compose -p memory up -d --no-deps service\n```",
                "评测令牌 mn_Rwa3jKpQ2sT7vX1yZ8aB4cD6eF0gH2iJ4kL 存在 600 权限文件里",
                "白绫因为老开合扇子吵到人而被群主要求收起扇子",
                // 1.3.8 起状态量只在 reason() 里查：agent 正当存一条「剩余 50」不该被拦
                "连接池剩余 50 个空闲连接，maxTotal 200"
        };
        for (String sample : keep) {
            assertNull(NoiseFilter.scaffolding(sample), "不该被拦下: " + head(sample)
                    + " → " + NoiseFilter.scaffolding(sample));
        }
    }

    private static String head(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 30 ? t : t.substring(0, 30) + "…";
    }
}
