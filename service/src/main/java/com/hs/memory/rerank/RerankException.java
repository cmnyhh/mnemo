package com.hs.memory.rerank;

/** 精排不可用或调用失败。调用方据此退回 RRF 顺序，不向上抛。 */
public class RerankException extends Exception {

    /** 这类失败重试一次可能就好了（限流、超时、5xx）。 */
    private final boolean retryable;

    public RerankException(String message) {
        this(message, null, false);
    }

    public RerankException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public RerankException(String message, boolean retryable) {
        this(message, null, retryable);
    }

    public RerankException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
