package com.hs.memory.admin;

import com.hs.memory.config.RuntimeConfig;
import com.hs.memory.embed.EmbeddingClient;
import com.hs.memory.repo.MemoryRepository;
import com.hs.memory.vector.QdrantClient;
import com.hs.memory.vector.VecHash;
import com.hs.memory.vector.VectorRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 重建向量索引。
 *
 * <p>为什么必须有这个按钮：embedding 从 hash 换成真模型、或换模型/维度，旧向量与新查询
 * 根本不在同一个空间里。此时不重建，向量这一路要么报维度错误、要么用「语义上无意义」的
 * 距离把噪声排到前面——两种都比「没有向量」更糟。
 *
 * <p>做法是删掉集合并按新维度重建，再把 PG 里所有 active 记录重新嵌入一遍。
 * 向量是派生数据，可以随时从叙述文本重算；真正的账本始终在 PG。
 */
@Service
public class ReindexService {

    private static final Logger log = LoggerFactory.getLogger(ReindexService.class);
    private static final int BATCH = 100;

    private final RuntimeConfig config;
    private final MemoryRepository repo;
    private final QdrantClient qdrant;
    private final VectorRegistry vectors;
    private final AtomicReference<State> state = new AtomicReference<>(State.idle());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-reindex");
        t.setDaemon(true);
        return t;
    });

    public ReindexService(RuntimeConfig config, MemoryRepository repo, QdrantClient qdrant,
                          VectorRegistry vectors) {
        this.config = config;
        this.repo = repo;
        this.qdrant = qdrant;
        this.vectors = vectors;
    }

    public State state() {
        return state.get();
    }

    /** 幂等：已经在跑就直接返回当前状态，不排第二个任务。 */
    public synchronized State start() {
        if ("running".equals(state.get().status())) {
            return state.get();
        }
        RuntimeConfig.Snapshot snap = config.snapshot();
        String tenant = config.settings().tenant();
        long total = repo.countActive(tenant);
        State initial = new State("running", total, 0, 0, 0,
                Instant.now(), null, snap.spaceId(), snap.fingerprint(), null);
        state.set(initial);
        worker.submit(() -> run(snap, tenant, total));
        return initial;
    }

    private void run(RuntimeConfig.Snapshot snap, String tenant, long total) {
        long embedded = 0;
        long failed = 0;
        try {
            EmbeddingClient client = config.embedding();
            // 先探一次：端点错/模型名错/维度错，应该在删集合之前就暴露。
            float[] probe = client.embed("维度自检");
            if (probe.length != snap.embeddingDims()) {
                throw new IllegalStateException("模型返回 " + probe.length + " 维，配置是 "
                        + snap.embeddingDims() + " 维");
            }

            qdrant.recreateCollection(probe.length);
            log.info("重建索引开始: total={} space={} dims={}", total, snap.spaceId(), probe.length);

            UUID after = null;
            while (true) {
                List<MemoryRepository.ReindexRow> rows = repo.activeForReindex(tenant, after, BATCH);
                if (rows.isEmpty()) {
                    break;
                }
                for (MemoryRepository.ReindexRow row : rows) {
                    try {
                        float[] vector = client.embed(row.narrative());
                        String vecHash = VecHash.of(snap.spaceId(), vector);
                        vectors.pending(row.tenantId(), row.id(), snap.spaceId(), vector.length, vecHash);
                        qdrant.upsert(row.id(), vector, payload(row, vecHash));
                        vectors.ack(row.tenantId(), row.id(), snap.spaceId(), vector.length, vecHash);
                        embedded++;
                    } catch (Exception e) {
                        failed++;
                        log.warn("重建单条失败 id={}: {}", row.id(), e.getMessage());
                    }
                }
                after = rows.get(rows.size() - 1).id();
                state.set(new State("running", total, embedded + failed, embedded, failed,
                        state.get().startedAt(), null, snap.spaceId(), snap.fingerprint(), null));
                Thread.sleep(20); // 别把单核占满，BM25 召回还要能用
            }

            repo.seedSpace(snap.spaceId(), snap.embeddingProvider(), snap.embeddingModel(),
                    config.settings().qdrantCollection(), snap.embeddingDims());
            config.markReindexed();
            String message = failed == 0
                    ? "完成：嵌入 " + embedded + " 条"
                    : "完成，但有 " + failed + " 条失败（可再次运行补齐）";
            state.set(new State("done", total, embedded + failed, embedded, failed,
                    state.get().startedAt(), Instant.now(), snap.spaceId(), snap.fingerprint(), message));
            log.info("重建索引完成: embedded={} failed={}", embedded, failed);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            state.set(new State("failed", total, embedded + failed, embedded, failed,
                    state.get().startedAt(), Instant.now(), snap.spaceId(), snap.fingerprint(), message));
            log.error("重建索引失败: {}", message);
        }
    }

    private static Map<String, Object> payload(MemoryRepository.ReindexRow row, String vecHash) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenant_id", row.tenantId());
        payload.put("status", "active");
        payload.put("kind", row.kind());
        payload.put("tier", row.tier());
        if (row.projectId() != null) {
            payload.put("project_id", row.projectId());
        }
        if (row.sessionId() != null) {
            payload.put("session_id", row.sessionId());
        }
        payload.put("valid_from", row.validFrom() == null ? Instant.now().getEpochSecond()
                : row.validFrom().getEpochSecond());
        // 摘要进 payload 是 I10 的前提：巡检比的是「两边记的是不是同一份」，
        // 从后端反算浮点既慢又受精度影响，写进去才有一致性可言。
        payload.put("vec_hash", vecHash);
        return payload;
    }

    /**
     * @param processed 已处理条数（成功 + 失败）
     * @param embedded  成功写入向量库的条数
     */
    public record State(
            String status,
            long total,
            long processed,
            long embedded,
            long failed,
            Instant startedAt,
            Instant finishedAt,
            String spaceId,
            String fingerprint,
            String message) {

        static State idle() {
            return new State("idle", 0, 0, 0, 0, null, null, null, null, null);
        }

        public double percent() {
            return total <= 0 ? 0 : Math.min(100, Math.round(processed * 1000.0 / total) / 10.0);
        }
    }
}
