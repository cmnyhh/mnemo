package com.hs.memory.embed;

import java.util.List;

/**
 * 本地确定性向量：只为把「写入 → 向量化 → 检索 → 融合」整条链路跑通，没有任何语义能力。
 *
 * <p>它保证同一段文本永远得到同一个向量，所以适合做冒烟测试和离线回归；
 * 真实召回必须把 MEMORY_EMBEDDING_PROVIDER 切到 http（见 {@link HttpEmbeddingClient}）。
 * 这一点在 README 里也必须写明——否则会有人以为向量检索已经在工作。
 */
public class HashEmbeddingClient implements EmbeddingClient {

    private final int dims;
    private final String model;

    public HashEmbeddingClient(int dims, String model) {
        this.dims = dims;
        this.model = model;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dims];
        List<String> tokens = Tokenizer.tokenize(text);
        if (tokens.isEmpty()) {
            v[0] = 1f;
            return v;
        }
        for (String t : tokens) {
            long h = fnv1a(t);
            int idx = (int) Math.floorMod(h, (long) dims);
            float sign = ((h >>> 63) & 1L) == 0L ? 1f : -1f;
            v[idx] += sign;
        }
        normalize(v);
        return v;
    }

    @Override
    public int dims() {
        return dims;
    }

    @Override
    public String model() {
        return model;
    }

    private static void normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        if (sum == 0) {
            v[0] = 1f;
            return;
        }
        float inv = (float) (1.0 / Math.sqrt(sum));
        for (int i = 0; i < v.length; i++) {
            v[i] *= inv;
        }
    }

    private static long fnv1a(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
