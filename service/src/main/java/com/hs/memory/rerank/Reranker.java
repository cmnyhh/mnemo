package com.hs.memory.rerank;

import java.util.List;

/**
 * 精排（cross-encoder）客户端。
 *
 * <p>为什么需要它：向量检索是 bi-encoder——query 和文档各自独立编码，只在最后比余弦。
 * 它对「同语言、同领域」的短文本分辨力有限，无关条目往往也能拿到 0.3~0.5 的基础分，
 * 而 RRF 只看名次，改不动这件事。cross-encoder 把 query 与文档拼在一起过一遍模型，
 * 输出的就是「这一对有多相关」，这才是能把 0.62 和 0.58 分开的东西。
 *
 * <p>契约：{@link #score} 返回与入参等长的数组，值域 0..1；不可用或调用失败时抛
 * {@link RerankException}，由调用方降级为 RRF 顺序——少一次精排，不能让整次召回失败。
 */
public interface Reranker {

    /** 与 {@code docs} 等长、值域 0..1 的相关度。失败抛 {@link RerankException}。 */
    double[] score(String query, List<String> docs) throws RerankException;

    /** 当前是否真的能精排（未配置时为 false，调用方据此跳过并保持旧行为）。 */
    boolean available();

    /** 写进响应/日志的一行描述，便于排障时知道「这一轮到底排没排」。 */
    String describe();
}
