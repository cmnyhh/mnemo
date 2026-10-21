package com.hs.memory.tier;

import com.hs.memory.Settings;
import com.hs.memory.repo.MemoryRepository;
import com.hs.memory.search.Ranking;
import com.hs.memory.vector.QdrantClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 分层治理：晋升、降级、归档、复苏，以及常驻记忆的读取。
 *
 * <p>四个动作都用一条 UPDATE 完成，不做「先查出来再逐条改」：
 * 判定条件和数据在同一个事务里，不会出现两条记录按同一份旧快照互相覆盖。
 *
 * <p>PG 是层的唯一事实来源。Qdrant payload 里的 tier 只是写入时的快照，
 * 这里改完层会尽力同步；同步失败不影响正确性，因为检索结果的层级以 PG 行为准，
 * 向量侧的 tier 只用于「把明显不该出现的点提前过滤掉」。
 */
@Service
public class TierService {

    private static final Logger log = LoggerFactory.getLogger(TierService.class);
    /** 一次 payload 同步最多带多少个点。Qdrant 对单次请求体大小敏感，分批比调大超时稳。 */
    private static final int SYNC_BATCH = 200;

    private final JdbcTemplate jdbc;
    private final MemoryRepository repo;
    private final QdrantClient qdrant;
    private final Settings settings;

    public TierService(JdbcTemplate jdbc, MemoryRepository repo, QdrantClient qdrant, Settings settings) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.qdrant = qdrant;
        this.settings = settings;
    }

    /**
     * 定期治理。启动后延迟两分钟再跑：这段时间留给「容器刚起来、配置还没加载完」。
     * 失败只记日志——分层是优化，不该拖垮服务。
     */
    @Scheduled(fixedDelay = 1_800_000L, initialDelay = 120_000L)
    public void scheduled() {
        try {
            RebalanceResult r = rebalance();
            if (r.changed() > 0) {
                log.info("分层治理完成 降级={} 晋升={} 归档={} 复苏={} 同步={}",
                        r.demoted(), r.promoted(), r.archived(), r.revived(), r.synced());
            }
        } catch (Exception e) {
            log.warn("分层治理失败: {}", e.getMessage());
        }
    }

    public RebalanceResult rebalance() {
        int ttl = Math.max(1, settings.tierWorkingSetTtlHours());
        int archiveDays = Math.max(1, settings.tierArchiveAfterDays());
        int promoteAccess = Math.max(1, settings.tierPromoteAccess());

        List<Change> demoted = update("""
                update memory_item mi
                   set tier = 'T2', updated_at = now()
                  from (select tenant_id, session_id, max(created_at) as last_at
                          from memory_item
                         where status = 'active' and session_id is not null
                         group by tenant_id, session_id) s
                 where mi.tenant_id = s.tenant_id
                   and mi.session_id = s.session_id
                   and mi.status = 'active'
                   and mi.tier = 'T1'
                   and s.last_at < now() - (? * interval '1 hour')
                returning mi.id, mi.tier
                """, ttl);

        List<Change> promoted = update("""
                update memory_item
                   set tier = 'T1', updated_at = now()
                 where status = 'active' and tier = 'T2'
                   and trust_level <> 'low'
                   and access_count >= ?
                   and last_access_at > now() - (? * interval '1 day')
                returning id, tier
                """, promoteAccess, TierPolicy.PROMOTE_WINDOW_DAYS);

        List<Change> archived = update("""
                update memory_item
                   set tier = 'T3', updated_at = now()
                 where status = 'active'
                   and tier in ('T1', 'T2')
                   and kind <> 'preference'
                   and access_count <= 1
                   and importance < ?
                   and coalesce(last_access_at, created_at) < now() - (? * interval '1 day')
                returning id, tier
                """, TierPolicy.ARCHIVE_MAX_IMPORTANCE, archiveDays);

        List<Change> revived = update("""
                update memory_item
                   set tier = 'T2', updated_at = now()
                 where status = 'active' and tier = 'T3'
                   and last_access_at > now() - (? * interval '1 day')
                returning id, tier
                """, TierPolicy.REVIVE_WINDOW_DAYS);

        List<Change> all = new ArrayList<>();
        all.addAll(demoted);
        all.addAll(promoted);
        all.addAll(archived);
        all.addAll(revived);
        int synced = syncPayload(all);
        return new RebalanceResult(demoted.size(), promoted.size(), archived.size(), revived.size(), synced);
    }

    private List<Change> update(String sql, Object... args) {
        return jdbc.query(sql, (rs, i) -> new Change(rs.getObject("id", UUID.class), rs.getString("tier")), args);
    }

    /** 把新层写回向量库。失败只记一次总数，不逐条刷日志。 */
    private int syncPayload(List<Change> changes) {
        if (changes.isEmpty()) {
            return 0;
        }
        Map<String, List<UUID>> byTier = new LinkedHashMap<>();
        for (Change c : changes) {
            byTier.computeIfAbsent(c.tier(), k -> new ArrayList<>()).add(c.id());
        }
        int ok = 0;
        for (Map.Entry<String, List<UUID>> e : byTier.entrySet()) {
            List<UUID> ids = e.getValue();
            for (int from = 0; from < ids.size(); from += SYNC_BATCH) {
                List<UUID> batch = ids.subList(from, Math.min(from + SYNC_BATCH, ids.size()));
                try {
                    qdrant.setPayload(batch, Map.of("tier", e.getKey()));
                    ok += batch.size();
                } catch (Exception ex) {
                    log.warn("向量 payload 同步失败 tier={} 条数={}: {}", e.getKey(), batch.size(), ex.getMessage());
                }
            }
        }
        return ok;
    }

    /**
     * 常驻记忆。按「重要度 → 被引用次数 → 最近更新」排，不按时间倒序：
     * 一条三天前钉住的偏好，比今天顺手钉的一条日志更该每轮出现。
     */
    public Pinned pinned(String tenantId, Integer budgetChars) {
        int budget = budgetChars == null || budgetChars <= 0
                ? settings.tierPinnedBudgetChars() : budgetChars;
        List<MemoryRepository.PinnedRow> rows = repo.listPinned(tenantId, 100);
        List<Ranking.InjectionEntry> entries = new ArrayList<>();
        for (MemoryRepository.PinnedRow r : rows) {
            entries.add(new Ranking.InjectionEntry(r.kind(), r.tier(), r.importance(), r.narrative(),
                    r.trustLevel(), List.of()));
        }
        String injection = Ranking.renderPinned(entries, budget);
        List<PinnedItem> items = new ArrayList<>();
        for (MemoryRepository.PinnedRow r : rows) {
            items.add(new PinnedItem(r.id().toString(), r.kind(), r.tier(), r.narrative(), r.importance()));
        }
        return new Pinned(injection.isBlank() ? 0 : items.size(), budget, injection, items);
    }

    /**
     * 钉住 / 取消钉住。取消时回 T2 而不是回到「原来的层」：
     * 钉住期间原层可能已经被治理改过，记一个「钉之前是几层」反而会制造错误的状态。
     */
    public boolean pin(String tenantId, UUID id, boolean pinned) {
        if (pinned) {
            // 投毒防线的落点：常驻层每轮都注入，低可信内容一旦钉上去就等于永久暗示。
            String trust = repo.findTrust(tenantId, id).orElse(null);
            if (trust != null && com.hs.memory.guard.TrustPolicy.LOW.equals(
                    com.hs.memory.guard.TrustPolicy.norm(trust))) {
                throw new IllegalArgumentException("low 可信内容不能设为常驻；先核实来源，再以高可信来源重新写入");
            }
        }
        String tier = pinned ? TierPolicy.T0 : TierPolicy.T2;
        boolean ok = repo.setTier(tenantId, id, tier);
        if (ok) {
            try {
                qdrant.setPayload(List.of(id), Map.of("tier", tier));
            } catch (Exception e) {
                log.warn("钉住后向量 payload 未同步 id={}: {}", id, e.getMessage());
            }
        }
        return ok;
    }

    public record Change(UUID id, String tier) {
    }

    public record RebalanceResult(int demoted, int promoted, int archived, int revived, int synced) {
        public int changed() {
            return demoted + promoted + archived + revived;
        }
    }

    public record Pinned(int count, int budgetChars, String injection, List<PinnedItem> items) {
    }

    public record PinnedItem(String id, String kind, String tier, String narrative, double importance) {
    }
}
