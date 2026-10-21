package com.hs.memory.extract;

/**
 * 没配抽取模型时的占位。
 *
 * <p>它的存在是为了让调用方不必到处判空：{@link #available()} 返回 false 时，
 * 上游走按段硬切的兜底路径，行为与 0.5.0 一致。
 */
public class NoopExtractor implements Extractor {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public ExtractResult extract(String text) {
        throw new ExtractException("未配置抽取模型");
    }

    @Override
    public String describe() {
        return "none";
    }
}
