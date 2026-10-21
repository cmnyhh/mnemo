package com.hs.memory.api;

import com.hs.memory.asset.AssetService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件接口。
 *
 * <p>上传用 multipart 而不是 base64 JSON：插件侧拿到的是 Buffer，多一层编码只是让
 * 20MB 的图变成 27MB 的请求体，且 JSON 解析会多占一份内存。
 *
 * <p>读取直接回字节流，Content-Type 用存下来的 mime。这样调用方（插件 / 浏览器）
 * 可以把它当图片渲染，而不需要知道存储布局。
 */
@RestController
@RequestMapping("/v1/memory/assets")
public class AssetController {

    private final AssetService assets;

    public AssetController(AssetService assets) {
        this.assets = assets;
    }

    @PostMapping
    public AssetService.AssetView upload(
            @RequestAttribute("tenant") String tenant,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String memoryId,
            @RequestParam(required = false) String caption,
            @RequestParam(required = false) String ocr,
            @RequestParam(required = false, defaultValue = "true") boolean createMemory,
            @RequestParam(required = false) String projectId,
            @RequestAttribute(name = "tokenId", required = false) String tokenId) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file 不能为空");
        }
        return assets.upload(tenant, file.getBytes(), file.getOriginalFilename(),
                file.getContentType(), memoryId, caption, ocr, createMemory, projectId, tokenId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(@RequestAttribute("tenant") String tenant,
                                      @PathVariable String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("id 需为 uuid");
        }
        AssetService.Loaded loaded = assets.load(tenant, uuid);
        MediaType type;
        try {
            type = MediaType.parseMediaType(loaded.mimeType());
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        String name = loaded.fileName() == null ? uuid.toString() : loaded.fileName();
        // 文件名可能有非 ASCII：用 RFC 5987 的 filename* 而不是裸 filename
        String encoded = java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + encoded)
                // 内容是内容寻址的，同一个 id 的字节永远不变
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePublic())
                .body(loaded.data());
    }
}
