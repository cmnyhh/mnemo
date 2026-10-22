package com.hs.memory.extract;

/**
 * 没配模型时的占位，行为等同「摘要能力关闭」：调用方回落原文拼接。
 */
public class NoopSummarizer implements Summarizer {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Summary summarize(String text) {
        throw new ExtractException("未配置摘要模型");
    }

    @Override
    public String describe() {
        return "none";
    }
}
