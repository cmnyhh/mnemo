package com.hs.memory.asset;

/** 没配看图模型时的占位实现。上传照常可用，只是没有自动图注。 */
public class NoopVisionClient implements VisionClient {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Caption describe(byte[] image, String mimeType) throws VisionException {
        throw new VisionException("未配置看图模型（memory.vision.provider=none）");
    }
}
