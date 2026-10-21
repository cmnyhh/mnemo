package com.hs.memory.asset;

/** 看图失败。调用方一律降级为「只存图不写图注」，不向上抛。 */
public class VisionException extends Exception {

    public VisionException(String message) {
        super(message);
    }

    public VisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
