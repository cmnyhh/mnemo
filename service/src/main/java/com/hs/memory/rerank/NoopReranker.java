package com.hs.memory.rerank;

import java.util.List;

/**
 * 未配置精排模型时的占位实现：明确报告「不可用」，让调用方走跳过分支，
 * 而不是伪造一组分数把 rerank 权重白白算进去。
 */
public class NoopReranker implements Reranker {

    @Override
    public double[] score(String query, List<String> docs) throws RerankException {
        throw new RerankException("未配置精排模型");
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String describe() {
        return "none（未配置精排，按 RRF 名次输出）";
    }
}
