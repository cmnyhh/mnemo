package com.hs.memory.admin;

import com.hs.memory.Settings;
import com.hs.memory.vector.QdrantClient;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 磁盘与数据量账目。
 *
 * <p>设计取向：**被取代的记忆不删**。它们是「曾经记过什么」的证据，用户可能就是要翻旧账；
 * 只是不再参与召回（检索只看 active）。代价是向量库里留着不再使用的副本，
 * 所以这里把「当前在用」和「保留的副本」分开报账，并给一个只删副本、不动条目的清理入口。
 *
 * <p>清理不会让任何记忆消失：向量是可以从 PG 重算的派生数据，
 * 而记忆本身留在 memory_item 里，控制台按状态就能翻到。
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    /** 一次删多少点。批太大时 Qdrant 的 WAL 峰值会明显上去，收益却有限。 */
    private static final int DELETE_BATCH = 500;

    private final JdbcTemplate jdbc;
    private final QdrantClient qdrant;
    private final Settings settings;

    public StorageService(JdbcTemplate jdbc, QdrantClient qdrant, Settings settings) {
        this.jdbc = jdbc;
        this.qdrant = qdrant;
        this.settings = settings;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("postgres", postgres());
        out.put("vectors", vectors());
        out.put("assets", assets());
        out.put("disk", disk());
        return out;
    }

    // ------------------------------------------------------------------ 各项账目

    private Map<String, Object> postgres() {
        Map<String, Object> pg = new LinkedHashMap<>();
        try {
            pg.put("bytes", jdbc.queryForObject("select pg_database_size(current_database())", Long.class));
        } catch (Exception e) {
            pg.put("error", e.getMessage());
        }
        Map<String, Long> byStatus = new TreeMap<>();
        try {
            jdbc.query("select status, count(*) as n from memory_item group by 1", rs -> {
                byStatus.put(rs.getString("status"), rs.getLong("n"));
            });
        } catch (Exception e) {
            pg.put("error", e.getMessage());
        }
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long active = byStatus.getOrDefault("active", 0L);
        pg.put("items", total);
        pg.put("active", active);
        // 被取代 / 已遗忘：留着可以翻，但不参与召回
        pg.put("retained", total - active);
        pg.put("byStatus", byStatus);
        return pg;
    }

    private Map<String, Object> vectors() {
        Map<String, Object> vec = new LinkedHashMap<>();
        vec.put("collection", settings.qdrantCollection());
        long active = countActive();
        vec.put("active", active);
        try {
            Integer dims = qdrant.collectionDims();
            long points = qdrant.count();
            vec.put("dims", dims);
            vec.put("points", points);
            // 向量本体是 float32；payload 与 HNSW 图另占一份，这里只报向量，
            // 所以前端用「约」——不假装这是精确的磁盘占用。
            long dim = dims == null ? 0 : dims;
            vec.put("bytes", points * dim * 4L);
            vec.put("retained", Math.max(0, points - active));
        } catch (Exception e) {
            vec.put("error", e.getMessage());
        }
        return vec;
    }

    private Map<String, Object> assets() {
        Map<String, Object> out = new LinkedHashMap<>();
        long files = 0;
        long bytes = 0;
        Path dir = settings.assetsDir() == null ? null : Path.of(settings.assetsDir());
        if (dir != null && Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (Files.isRegularFile(p)) {
                        files++;
                        try {
                            bytes += Files.size(p);
                        } catch (IOException ignored) {
                            // 文件在扫描过程中被删：少算一张不影响结论
                        }
                    }
                }
            } catch (IOException e) {
                out.put("error", e.getMessage());
            }
        }
        out.put("files", files);
        out.put("bytes", bytes);
        out.put("dir", dir == null ? null : dir.toString());
        try {
            out.put("records", jdbc.queryForObject("select count(*) from memory_asset", Long.class));
        } catch (Exception ignored) {
            // 表不存在时不给这一项
        }
        return out;
    }

    private Map<String, Object> disk() {
        Map<String, Object> out = new LinkedHashMap<>();
        Path probe = existingAncestor(settings.assetsDir());
        if (probe == null) {
            return out;
        }
        try {
            FileStore fs = Files.getFileStore(probe);
            out.put("total", fs.getTotalSpace());
            out.put("usable", fs.getUsableSpace());
        } catch (IOException e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------ 清理

    /**
     * 删掉不再参与召回的向量副本（superseded / deleted）。
     *
     * <p>只动向量，不动 memory_item：条目仍然可以在「记忆库」里按状态筛出来，
     * 只是搜索不再命中它——本来也命不中，检索只查 active。
     */
    public Map<String, Object> cleanupVectors() {
        long t0 = System.currentTimeMillis();
        List<UUID> ids;
        try {
            ids = jdbc.queryForList("select id from memory_item where status <> 'active'", UUID.class);
        } catch (Exception e) {
            throw new IllegalStateException("读取待清理条目失败：" + e.getMessage(), e);
        }

        long before = -1;
        try {
            before = qdrant.count();
        } catch (Exception ignored) {
            // 读不到起点就只报条目数
        }

        int deleted = 0;
        int failed = 0;
        for (int i = 0; i < ids.size(); i += DELETE_BATCH) {
            List<UUID> batch = new ArrayList<>(ids.subList(i, Math.min(ids.size(), i + DELETE_BATCH)));
            try {
                qdrant.delete(batch);
                deleted += batch.size();
            } catch (Exception e) {
                failed += batch.size();
                log.warn("清理向量失败（{} 条）：{}", batch.size(), e.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        // 条目数与真正删掉的点数分开报：软删时向量就已经删过了，
        // 这些条目再走一遍清理不会真的减少点，混为一谈会让人以为磁盘没回收。
        out.put("records", ids.size());
        out.put("deleted", deleted);
        out.put("failed", failed);
        out.put("elapsedMs", System.currentTimeMillis() - t0);
        try {
            long after = qdrant.count();
            out.put("pointsAfter", after);
            out.put("pointsRemoved", before >= 0 ? Math.max(0, before - after) : null);
        } catch (Exception ignored) {
            // 清理完读不到总数不影响结论
        }
        if (failed > 0) {
            out.put("note", "有 " + failed + " 条向量没删掉，可以稍后重试");
        }
        return out;
    }

    private long countActive() {
        try {
            Long n = jdbc.queryForObject("select count(*) from memory_item where status = 'active'", Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 目录可能还没建（没上传过附件）：往上找到第一个存在的祖先，用它所在的文件系统。 */
    private static Path existingAncestor(String dir) {
        if (dir == null || dir.isBlank()) {
            return null;
        }
        Path p = Path.of(dir).toAbsolutePath();
        while (p != null && !Files.exists(p)) {
            p = p.getParent();
        }
        return p;
    }
}
