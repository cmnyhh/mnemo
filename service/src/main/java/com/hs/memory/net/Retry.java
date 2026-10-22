package com.hs.memory.net;

/**
 * 公网推理端点的重试判定。
 *
 * <p>只重试「等一下可能就好」的失败：429（配额/限流）、408、5xx、连接超时。
 * 4xx 里的其余状态是请求本身有问题（key 错、模型名错、参数错），重试只会白烧一次超时预算。
 *
 * <p>只重试一次。这条链路在每轮对话的同步路径上，两次尝试已经是可用性的上限；
 * 再多就把「慢」变成了「不可用」，而慢和不可用对用户是一回事。
 */
public final class Retry {

    /** 总尝试次数（含第一次）。 */
    public static final int ATTEMPTS = 2;

    /** 两次尝试之间的间隔：避开瞬时抖动即可，不做过长的指数退避。 */
    public static final long BACKOFF_MS = 120;

    private Retry() {
    }

    /** 429 / 408 / 5xx 值得重试。 */
    public static boolean retryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    public static void backoff() {
        try {
            Thread.sleep(BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
