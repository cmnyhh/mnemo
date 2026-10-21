package com.hs.memory.extract;

/** 抽取失败。上层据此整批丢弃并记失败计数，绝不写半成品。 */
public class ExtractException extends RuntimeException {

    public ExtractException(String message) {
        super(message);
    }

    public ExtractException(String message, Throwable cause) {
        super(message, cause);
    }
}
