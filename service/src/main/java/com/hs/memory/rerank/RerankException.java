package com.hs.memory.rerank;

/** 精排不可用或调用失败。调用方据此退回 RRF 顺序，不向上抛。 */
public class RerankException extends Exception {

    public RerankException(String message) {
        super(message);
    }

    public RerankException(String message, Throwable cause) {
        super(message, cause);
    }
}
