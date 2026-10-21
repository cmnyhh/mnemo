package com.hs.memory.asset;

import com.hs.memory.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 附件文件存储。目录布局是 {@code <root>/ab/<sha256>.<ext>}：
 * 两级分片只为避免单目录堆几万个文件，对单机规模其实可有可无，但改起来是零成本。
 *
 * <p>文件名就是内容哈希，所以「同一张图传两次」在磁盘上只有一份。
 * 幂等由内容寻址天然保证，不需要额外的引用计数——删记录时文件留着也不影响正确性，
 * 只是多占一点空间；反过来（先删文件）会让别的记录指向不存在的附件。
 */
@Component
public class AssetStore {

    /** 允许的扩展名。白名单而不是「取原文件后缀」：后缀会进 URL 与磁盘路径，不该由调用方决定。 */
    private static final Set<String> EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg",
            "mp3", "wav", "m4a", "ogg", "flac",
            "mp4", "mov", "webm",
            "pdf", "txt", "md", "csv", "json",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip");

    private final Path root;
    private final long maxBytes;

    /**
     * 有两个构造器（另一个给测试用），所以必须显式标 @Autowired：
     * Spring 只在「唯一构造器」时自动装配，多一个就报 No default constructor found。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AssetStore(Settings settings) {
        this(Paths.get(settings.assetsDir()).toAbsolutePath().normalize(), settings.assetMaxBytes());
    }

    /** 测试用：直接给根目录，不经过 Settings。 */
    AssetStore(Path root, long maxBytes) {
        this.root = root.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    public record Stored(String storageKey, long bytes, String sha256) {
    }

    public long maxBytes() {
        return maxBytes;
    }

    public Path root() {
        return root;
    }

    /**
     * 落盘。先写 {@code .part} 再原子改名：读的一方永远看不到写了一半的文件。
     * 目标已存在时直接返回——同样的内容没必要再写一遍。
     */
    public Stored save(byte[] data, String ext) throws IOException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("文件为空");
        }
        if (data.length > maxBytes) {
            throw new IllegalArgumentException("文件超过上限 " + maxBytes + " 字节");
        }
        String sha = sha256(data);
        String normalized = normalizeExt(ext);
        String key = sha.substring(0, 2) + "/" + sha + (normalized.isEmpty() ? "" : "." + normalized);
        Path target = resolve(key);
        if (Files.exists(target)) {
            return new Stored(key, data.length, sha);
        }
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), "upload", ".part");
        try {
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.FileAlreadyExistsException race) {
            // 并发上传同一份内容：对方先写完了，用它的
            Files.deleteIfExists(tmp);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return new Stored(key, data.length, sha);
    }

    public byte[] read(String storageKey) throws IOException {
        return Files.readAllBytes(resolve(storageKey));
    }

    public boolean exists(String storageKey) {
        try {
            return Files.isReadable(resolve(storageKey));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 只接受 {@code ab/<64 hex>[.ext]} 这一种形状，且必须落在 root 之内。
     * 存储键会从数据库读出来，而数据库曾经被外部写入过——不校验就等于把路径穿越
     * 的入口留在读接口上。
     */
    private Path resolve(String storageKey) {
        if (storageKey == null || !storageKey.matches("[0-9a-f]{2}/[0-9a-f]{64}(\\.[a-z0-9]{1,8})?")) {
            throw new IllegalArgumentException("非法的存储键");
        }
        Path p = root.resolve(storageKey).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("存储键越出附件目录");
        }
        return p;
    }

    /** 从文件名后缀或 MIME 推断扩展名；推断不出来就留空（文件仍可存可取）。 */
    public static String extOf(String filename, String mime) {
        String fromName = "";
        if (filename != null && filename.lastIndexOf('.') >= 0) {
            fromName = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        }
        if (EXTENSIONS.contains(fromName)) {
            return fromName;
        }
        String fromMime = switch (mime == null ? "" : mime.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            case "audio/mpeg" -> "mp3";
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/mp4" -> "m4a";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "application/pdf" -> "pdf";
            case "text/plain" -> "txt";
            case "text/markdown" -> "md";
            default -> "";
        };
        return fromMime;
    }

    private static String normalizeExt(String ext) {
        String e = ext == null ? "" : ext.trim().toLowerCase(Locale.ROOT).replace(".", "");
        return EXTENSIONS.contains(e) ? e : "";
    }

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
