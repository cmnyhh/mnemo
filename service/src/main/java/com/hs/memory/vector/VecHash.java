package com.hs.memory.vector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 向量摘要：不存向量也能回答「后端里那份是不是这份」。
 *
 * <p>为什么把 space 也算进去：同一个 float[] 在不同空间里含义不同。只对向量做哈希，
 * 换模型后新旧两条向量偶尔数值相同就会误判成「一致」，而对账要防的正是换空间漏重建。
 *
 * <p>用 float32 小端字节而不是 {@code Arrays.hashCode}：后者是 32 位、冲突率不适合对账。
 */
public final class VecHash {

    private VecHash() {
    }

    public static String of(String spaceId, float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("空向量没有摘要");
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(spaceId.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) '|');
            ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (float v : vector) {
                buffer.putFloat(v);
            }
            sha.update(buffer.array());
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 没有 SHA-256", e);
        }
    }
}
