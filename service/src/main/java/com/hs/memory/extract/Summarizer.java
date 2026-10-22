package com.hs.memory.extract;

/**
 * 把一轮对话压成一条摘要。
 *
 * <p>和 {@link Extractor} 分开，是因为两者的失败代价相反：抽取失败只是「这一轮没沉淀成事实」，
 * 而摘要失败意味着「我们聊过什么都查不到了」。所以两者的解析取向也必须相反——
 * 抽取是严格的（形状不对就整批丢），摘要是宽松的（模型没吐 JSON 也按纯文本收下）。
 */
public interface Summarizer {

    /** 未配置模型时返回 false，调用方据此回落原文拼接。 */
    boolean available();

    /**
     * @throws ExtractException 调用失败、超时，或模型给了一个空摘要。
     *                          调用方应当回落原文，不要写半成品。
     */
    Summary summarize(String text) throws ExtractException;

    /** 用于日志与控制台展示，例如 {@code summary:http:glm-5.3-flash}。 */
    String describe();
}
