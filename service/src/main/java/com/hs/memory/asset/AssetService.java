package com.hs.memory.asset;

import com.hs.memory.Settings;
import com.hs.memory.api.IngestRequest;
import com.hs.memory.api.IngestResponse;
import com.hs.memory.ingest.IngestService;
import com.hs.memory.repo.MemoryRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 附件上传与读取。
 *
 * <p>设计上把「文件」和「记忆」分开：文件是内容寻址的（同图只存一份），
 * 记忆是语义的（同一张图在不同上下文里可以是两条不同的记忆）。上传时可以把附件挂到
 * 已有记忆上，也可以让服务自己建一条——后者是插件最常用的路径：用户贴一张报错截图，
 * 插件上传，服务生成一条「这张截图说了什么」的 episode，从此这张图能被文字检索到。
 *
 * <p>看图模型是可选增强：没配就只存原图 + 调用方给的说明，上传不会失败。
 */
@Service
public class AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);
    /** 图注写进来的记忆默认重要度：比随口一说的 episode 高，比明确的 fact 低。 */
    private static final double CAPTION_IMPORTANCE = 0.6;

    private final Settings settings;
    private final AssetStore store;
    private final AssetRepository assets;
    private final MemoryRepository memories;
    private final IngestService ingest;
    private final VisionClient vision;

    public AssetService(Settings settings, AssetStore store, AssetRepository assets,
                        MemoryRepository memories, IngestService ingest) {
        this.settings = settings;
        this.store = store;
        this.assets = assets;
        this.memories = memories;
        this.ingest = ingest;
        this.vision = settings.visionIsHttp() && settings.visionBaseUrl() != null
                && !settings.visionBaseUrl().isBlank()
                ? new HttpVisionClient(settings.visionBaseUrl(), settings.visionApiKey(),
                        settings.visionModel(), settings.visionTimeoutMs())
                : new NoopVisionClient();
    }

    public AssetView upload(String tenantId, byte[] data, String filename, String mimeType,
                            String memoryIdRaw, String captionRaw, String ocrRaw,
                            boolean createMemory, String projectId, String tokenIdRaw) {
        // 令牌 id 只作来源标记（memory_item.token_id），解析不出来也不影响上传。
        UUID tokenId = parseUuid(tokenIdRaw);
        String mime = mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType.trim();
        String kind = kindOf(mime, filename);
        String ext = AssetStore.extOf(filename, mime);

        UUID memoryId = parseUuid(memoryIdRaw);
        if (memoryId != null && memories.findByIds(tenantId, List.of(memoryId)).isEmpty()) {
            throw new IllegalArgumentException("memoryId 不存在: " + memoryIdRaw);
        }

        AssetStore.Stored stored;
        try {
            stored = store.save(data, ext);
        } catch (IOException e) {
            throw new IllegalStateException("附件落盘失败: " + e.getMessage(), e);
        }

        String caption = trimToNull(captionRaw);
        String ocr = trimToNull(ocrRaw);
        String note = null;
        if (caption == null && "image".equals(kind) && vision.available()) {
            try {
                VisionClient.Caption c = vision.describe(data, mime);
                caption = trimToNull(c.caption());
                ocr = ocr == null ? trimToNull(c.ocr()) : ocr;
                log.info("图注生成 model={} latency={}ms chars={}", c.model(), c.latencyMs(),
                        caption == null ? 0 : caption.length());
            } catch (VisionException e) {
                // 上传成功但没有图注，是降级而不是失败；不写 note 的话用户会以为模型没被调用
                note = "图注生成失败，已保存原图: " + e.getMessage();
                log.warn("图注生成失败: {}", e.getMessage());
            }
        }

        String narrative = joinNarrative(caption, ocr, filename);
        if (memoryId == null && createMemory) {
            if (narrative == null) {
                throw new IllegalArgumentException(
                        "没有 caption 也没有配置看图模型，无法为附件生成可检索的记忆；"
                                + "请提供 caption，或传 memoryId 挂到已有记忆上");
            }
            memoryId = createMemoryForAsset(tenantId, projectId, tokenId, stored.sha256(), narrative,
                    caption, ocr, filename, kind);
        }

        // 同一份文件挂在同一条记忆上：直接复用，别把重试变成两条记录。
        // 判断必须放在 memoryId 确定之后——上传时它可能还是 null（由 createMemory 生成），
        // 在 null 上查重永远查不到第一行，重传就会多出一条。
        Optional<AssetRepository.AssetRow> existing = assets.findBySha(tenantId, stored.sha256(), memoryId);
        if (existing.isPresent()) {
            return view(existing.get(), joinNote(note, "同一附件已存在，未重复写入"));
        }

        UUID id = assets.insert(tenantId, memoryId, kind, mime, stored.bytes(), stored.sha256(),
                stored.storageKey(), caption, ocr, filename);
        if (id == null) {
            throw new IllegalStateException("附件元数据写入失败");
        }
        AssetRepository.AssetRow row = assets.find(tenantId, id)
                .orElseThrow(() -> new IllegalStateException("附件写入后读不到"));
        return view(row, note);
    }

    /**
     * 用附件生成一条记忆。走正常的 ingest 通道而不是自己拼 SQL：
     * 分词、向量化、分层、幂等键都在那一处，绕过去就会出现「附件记忆搜不到」这类分叉。
     */
    private UUID createMemoryForAsset(String tenantId, String projectId, UUID tokenId, String sha256,
                                      String narrative, String caption, String ocr, String filename,
                                      String kind) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("asset_kind", kind);
        facts.put("asset_sha256", sha256);
        if (filename != null && !filename.isBlank()) {
            facts.put("asset_name", filename);
        }
        List<String> hints = new ArrayList<>();
        if (filename != null && !filename.isBlank()) {
            hints.add(filename);
        }
        if (caption != null) {
            hints.add(caption);
        }
        IngestRequest.Item item = new IngestRequest.Item(
                "episode", "project", null, narrative, facts, List.copyOf(hints),
                null, null, null, CAPTION_IMPORTANCE, 0.8, "high", null, null, "tool");
        IngestResponse resp = ingest.ingest(tenantId, tokenId, new IngestRequest(
                "asset:" + sha256, null, projectId, null, List.of(item), null));
        if (resp.items() == null || resp.items().isEmpty()) {
            throw new IllegalStateException("附件记忆未写入（抽取结果为空）");
        }
        return UUID.fromString(resp.items().get(0).id());
    }

    public Loaded load(String tenantId, UUID id) {
        AssetRepository.AssetRow row = assets.find(tenantId, id)
                .orElseThrow(() -> new IllegalArgumentException("附件不存在: " + id));
        try {
            byte[] data = store.read(row.storageKey());
            return new Loaded(data, row.mimeType(), row.sourceName(), row.sha256());
        } catch (IOException e) {
            throw new IllegalStateException("附件文件读取失败: " + e.getMessage(), e);
        }
    }

    /** 召回时用：把命中的记忆 id 映射到附件视图，供注入块与响应体使用。 */
    public Map<UUID, List<AssetView>> byMemory(List<String> tenantIds, List<UUID> memoryIds) {
        // 单租户部署下 tenantIds 只有一条；留成列表是为了不把签名写死在单租户假设上
        if (memoryIds.isEmpty() || tenantIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<AssetView>> out = new LinkedHashMap<>();
        for (AssetRepository.AssetRow row : assets.byMemoryIds(tenantIds.get(0), memoryIds)) {
            out.computeIfAbsent(row.memoryId(), k -> new ArrayList<>()).add(view(row, null));
        }
        return out;
    }

    public long count(String tenantId) {
        return assets.count(tenantId);
    }

    public Map<String, Long> countByKind(String tenantId) {
        return assets.countByKind(tenantId);
    }

    private AssetView view(AssetRepository.AssetRow row, String note) {
        return new AssetView(
                row.id().toString(),
                row.memoryId() == null ? null : row.memoryId().toString(),
                row.kind(), row.mimeType(), row.bytes(), row.sha256(),
                row.caption(), row.ocrText(), row.sourceName(),
                "/v1/memory/assets/" + row.id(), note);
    }

    /** 文本都空时返回 null：让调用方决定是拒绝，还是只挂文件不建记忆。 */
    private static String joinNarrative(String caption, String ocr, String filename) {
        StringBuilder sb = new StringBuilder();
        if (caption != null) {
            sb.append(caption);
        }
        if (ocr != null) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("图中文字：").append(ocr);
        }
        if (sb.length() == 0) {
            return null;
        }
        return sb.toString();
    }

    static String kindOf(String mime, String filename) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/")) {
            return "image";
        }
        if (m.startsWith("audio/")) {
            return "audio";
        }
        if (m.startsWith("video/")) {
            return "video";
        }
        String f = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (f.matches(".*\\.(png|jpe?g|gif|webp|bmp|svg)$")) {
            return "image";
        }
        if (f.matches(".*\\.(mp3|wav|m4a|ogg|flac)$")) {
            return "audio";
        }
        if (f.matches(".*\\.(mp4|mov|webm)$")) {
            return "video";
        }
        return "document";
    }

    /** 两条说明都保留：既可能是「看图失败」，也可能是「重复上传」，合起来才是完整事实。 */
    private static String joinNote(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        return first + "；" + second;
    }

    private static UUID parseUuid(String raw) {
        String t = trimToNull(raw);
        if (t == null) {
            return null;
        }
        try {
            return UUID.fromString(t);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("memoryId 需为 uuid: " + raw);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public record AssetView(String id, String memoryId, String kind, String mimeType, long bytes,
                            String sha256, String caption, String ocr, String fileName,
                            String url, String note) {
    }

    public record Loaded(byte[] data, String mimeType, String fileName, String sha256) {
    }
}
