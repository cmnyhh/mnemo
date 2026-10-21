package com.hs.memory.vector;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 向量登记表的写入端（设计稿 §4.8）。
 *
 * <p>写两次是一个刻意的选择：先落 {@code backend_ack=false}，后端确认后再置 true。
 * 只写成功那一次的话，进程在 embed 与 upsert 之间被杀掉就什么痕迹都没有——
 * 而「向量丢了」恰恰就是这种时刻最难发现。先写 pending，巡检才有东西可看。
 *
 * <p>失败不抛给调用方：记忆已经进 PG，BM25 仍可用，向量可以重建。
 */
@Component
public class VectorRegistry {

    private static final Logger log = LoggerFactory.getLogger(VectorRegistry.class);

    private final JdbcTemplate jdbc;

    public VectorRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 登记「准备写」，ack 先置 false。 */
    public void pending(String tenantId, UUID itemId, String spaceId, int dims, String vecHash) {
        try {
            jdbc.update("""
                    insert into memory_vector (item_id, space_id, dims, vec_hash, tenant_id, backend_ack)
                    values (?, ?, ?, ?, ?, false)
                    on conflict (item_id, space_id) do update
                       set dims = excluded.dims, vec_hash = excluded.vec_hash,
                           tenant_id = excluded.tenant_id, backend_ack = false, updated_at = now()
                    """, itemId, spaceId, dims, vecHash, tenantId);
        } catch (Exception e) {
            log.warn("向量登记（pending）失败 item={}: {}", itemId, e.getMessage());
        }
    }

    /** 后端已确认写入。 */
    public void ack(String tenantId, UUID itemId, String spaceId, int dims, String vecHash) {
        try {
            jdbc.update("""
                    insert into memory_vector (item_id, space_id, dims, vec_hash, tenant_id, backend_ack)
                    values (?, ?, ?, ?, ?, true)
                    on conflict (item_id, space_id) do update
                       set dims = excluded.dims, vec_hash = excluded.vec_hash,
                           tenant_id = excluded.tenant_id, backend_ack = true, updated_at = now()
                    """, itemId, spaceId, dims, vecHash, tenantId);
        } catch (Exception e) {
            log.warn("向量登记（ack）失败 item={}: {}", itemId, e.getMessage());
        }
    }

    /** 巡检用：pending 超过 N 分钟的条数。 */
    public long pendingOlderThan(int minutes) {
        Long n = jdbc.queryForObject("""
                select count(*) from memory_vector
                 where not backend_ack and updated_at < now() - (? * interval '1 minute')
                """, Long.class, minutes);
        return n == null ? 0 : n;
    }
}
