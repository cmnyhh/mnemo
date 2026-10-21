package com.hs.memory.api;

import com.hs.memory.Settings;
import com.hs.memory.ingest.IngestService;
import com.hs.memory.repo.MemoryRepository;
import com.hs.memory.search.SearchService;
import com.hs.memory.tier.TierService;
import com.hs.memory.vector.QdrantClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/memory")
public class MemoryController {

    private final Settings settings;
    private final SearchService searchService;
    private final IngestService ingestService;
    private final TierService tierService;
    private final MemoryRepository repo;
    private final QdrantClient qdrant;
    private final JdbcTemplate jdbc;
    private final com.hs.memory.config.RuntimeConfig config;

    public MemoryController(Settings settings, SearchService searchService, IngestService ingestService,
                            TierService tierService, MemoryRepository repo, QdrantClient qdrant,
                            JdbcTemplate jdbc, com.hs.memory.config.RuntimeConfig config) {
        this.settings = settings;
        this.searchService = searchService;
        this.ingestService = ingestService;
        this.tierService = tierService;
        this.repo = repo;
        this.qdrant = qdrant;
        this.jdbc = jdbc;
        this.config = config;
    }

    /**
     * 存活探针：只回答「我活着」，不查依赖。查了会把 PG 抖动放大成重启风暴。
     *
     * <p>嵌入配置报的是<b>当前生效值</b>而不是环境变量基线：控制台改过模型之后，
     * 基线仍是 hash，照着 health 排障会得出「向量路是废的」这种错误结论。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        com.hs.memory.config.RuntimeConfig.Snapshot snapshot = config.snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("service", "memory-service");
        out.put("embeddingProvider", snapshot.embeddingProvider());
        out.put("embeddingModel", snapshot.embeddingModel());
        out.put("embeddingDims", snapshot.embeddingDims());
        out.put("rerank", snapshot.rerankProvider());
        return out;
    }

    /** 就绪探针：依赖不可用返回 503，让编排层不要把流量打进来。 */
    @GetMapping("/readyz")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean db;
        try {
            jdbc.queryForObject("select 1", Integer.class);
            db = true;
        } catch (Exception e) {
            db = false;
            out.put("dbError", e.getMessage());
        }
        boolean vec = qdrant.ping();
        out.put("db", db ? "ok" : "down");
        out.put("qdrant", vec ? "ok" : "down");
        out.put("status", db && vec ? "ready" : "not-ready");
        return ResponseEntity.status(db && vec ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(out);
    }

    @PostMapping("/search")
    public SearchResponse search(@RequestAttribute("tenant") String tenant,
                                 @RequestBody SearchRequest request) {
        return searchService.search(tenant, request);
    }

    @PostMapping("/ingest")
    public IngestResponse ingest(@RequestAttribute("tenant") String tenant,
                                 @RequestAttribute(name = "tokenId", required = false) String tokenId,
                                 @RequestBody IngestRequest request) {
        return ingestService.ingest(tenant, uuidOrNull(tokenId), request);
    }

    /** 令牌 id 只作来源标记，解析失败不该让整次写入失败。 */
    private static UUID uuidOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestAttribute("tenant") String tenant) {
        Map<String, Object> out = new LinkedHashMap<>(repo.stats(tenant));
        try {
            out.put("qdrantPoints", qdrant.count());
            out.put("qdrant", "ok");
        } catch (Exception e) {
            out.put("qdrant", "down");
            out.put("qdrantError", e.getMessage());
        }
        out.put("space", settings.spaceId());
        out.put("embeddingProvider", settings.embeddingProvider());
        return out;
    }

    /** 软删：PG 留痕，向量库删点。不硬删是设计稿 §8.3 的要求。 */
    @PostMapping("/forget")
    public ResponseEntity<Map<String, Object>> forget(@RequestAttribute("tenant") String tenant,
                                                      @RequestBody ForgetRequest request) {
        if (request.id() == null || request.id().isBlank()) {
            throw new IllegalArgumentException("id 不能为空");
        }
        UUID id;
        try {
            id = UUID.fromString(request.id().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("id 需为 uuid");
        }
        boolean deleted = repo.softDelete(tenant, id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", deleted);
        body.put("vectorDeleted", false);
        if (deleted) {
            try {
                qdrant.delete(List.of(id));
                body.put("vectorDeleted", true);
            } catch (Exception e) {
                // PG 已标记删除，向量残留不会让它被召回（召回以 PG 为准）
                body.put("note", "PG 已标记删除，Qdrant 删除失败: " + e.getMessage());
            }
        }
        return ResponseEntity.status(deleted ? HttpStatus.OK : HttpStatus.NOT_FOUND).body(body);
    }

    public record ForgetRequest(String id, String reason) {
    }

    /**
     * 常驻记忆。插件在每轮开始前拉一次，直接拼进系统提示——它不经过检索，
     * 所以「用户从不提这件事」也能生效。
     */
    @GetMapping("/pinned")
    public TierService.Pinned pinned(@RequestAttribute("tenant") String tenant,
                                     @org.springframework.web.bind.annotation.RequestParam(required = false)
                                     Integer budgetChars) {
        return tierService.pinned(tenant, budgetChars);
    }

    /** 钉住 / 取消钉住。T0 只能从这里进——自动晋升到常驻会让预算被悄悄占满。 */
    @PostMapping("/pin")
    public ResponseEntity<Map<String, Object>> pin(@RequestAttribute("tenant") String tenant,
                                                   @RequestBody PinRequest request) {
        if (request.id() == null || request.id().isBlank()) {
            throw new IllegalArgumentException("id 不能为空");
        }
        UUID id;
        try {
            id = UUID.fromString(request.id().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("id 需为 uuid");
        }
        boolean pinned = request.pinned() == null || request.pinned();
        boolean ok = tierService.pin(tenant, id, pinned);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("updated", ok);
        body.put("tier", pinned ? "T0" : "T2");
        return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.NOT_FOUND).body(body);
    }

    public record PinRequest(String id, Boolean pinned) {
    }

    /**
     * 手工触发一次分层治理。定时任务每 30 分钟跑一次，这个接口用于「改完参数立刻看效果」，
     * 也让升级后的第一轮分层不必等到下一个整点。
     */
    @PostMapping("/tiers/rebalance")
    public TierService.RebalanceResult rebalance() {
        return tierService.rebalance();
    }
}
