package com.hs.memory.extract;

/**
 * 把一段对话抽成结构化记忆。
 *
 * <p>这是整套系统里唯一「决定记什么」的地方：召回再准，也只可能在已经写进来的记忆里挑。
 * 所以抽取的取向是**宁可多抽**——低置信度的条目由 {@code confidence} 与 trust 在下游过滤，
 * 而不是在这里抠门。写漏了，后面任何调参都救不回来。
 */
public interface Extractor {

    /** 是否真的配置了抽取模型。未配置时上游回落为按段硬切。 */
    boolean available();

    /**
     * 抽取一段文本。
     *
     * @throws ExtractException 调用失败、超时、或模型输出不是合法的形式。
     *                          调用方应当整批丢弃，不要写半成品。
     */
    ExtractResult extract(String text) throws ExtractException;

    /** 用于日志与控制台展示，例如 {@code http:qwen2.5-7b-instruct}。 */
    String describe();
}
