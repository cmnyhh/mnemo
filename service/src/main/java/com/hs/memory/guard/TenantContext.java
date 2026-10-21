package com.hs.memory.guard;

import java.util.function.Supplier;

/**
 * 当前请求的租户，供 RLS 会话变量（{@code app.tenant_id}）使用。
 *
 * <p>为什么是 ThreadLocal 而不是显式参数：租户要传到**连接**上，而连接是
 * {@code JdbcTemplate} 自己从池里借的，业务代码拿不到。ThreadLocal 是这里唯一
 * 不用把 tenantId 再穿一遍所有 DAO 签名的位置。
 *
 * <p>只包 {@code /v1/memory/*}：控制台与治理任务跑在表 owner 身份下，本来就要跨租户。
 * 这样 RLS 是「业务面」的纵深防御，不是把运维也一起锁死。
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static String get() {
        return CURRENT.get();
    }

    /** 嵌套调用恢复外层值，不 assuming「一定是最外层」——过滤器与内部重入都可能包一层。 */
    public static <T> T call(String tenantId, Supplier<T> body) {
        String previous = CURRENT.get();
        if (tenantId == null || tenantId.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(tenantId);
        }
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void run(String tenantId, Runnable body) {
        call(tenantId, () -> {
            body.run();
            return null;
        });
    }
}
