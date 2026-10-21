package com.hs.memory.embed;

public interface EmbeddingClient {

    /** 返回归一化前的原始向量；实现负责长度校验。 */
    float[] embed(String text);

    int dims();

    String model();
}
