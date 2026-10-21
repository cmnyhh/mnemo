package com.hs.memory.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetStoreTest {

    @TempDir
    Path root;

    private AssetStore store() {
        return new AssetStore(root, 1024 * 1024);
    }

    @Test
    void sameContentIsStoredOnce() throws Exception {
        AssetStore s = store();
        byte[] data = "hello memory".getBytes(StandardCharsets.UTF_8);
        AssetStore.Stored first = s.save(data, "png");
        AssetStore.Stored second = s.save(data, "png");

        assertThat(second.storageKey()).isEqualTo(first.storageKey());
        assertThat(first.storageKey()).endsWith(".png");
        assertThat(first.sha256()).isEqualTo(AssetStore.sha256(data));
        try (var files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1);
        }
    }

    @Test
    void readReturnsExactBytes() throws Exception {
        AssetStore s = store();
        byte[] data = new byte[]{1, 2, 3, 4, (byte) 0xff};
        AssetStore.Stored stored = s.save(data, "");
        assertThat(s.read(stored.storageKey())).isEqualTo(data);
        assertThat(stored.storageKey()).doesNotContain(".");
    }

    @Test
    void rejectsOversizedFile() {
        AssetStore small = new AssetStore(root, 4);
        assertThatThrownBy(() -> small.save(new byte[5], ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超过上限");
    }

    @Test
    void rejectsPathTraversalOnRead() {
        AssetStore s = store();
        // 存储键来自数据库；一旦被写坏，读接口不能变成任意文件读取
        assertThatThrownBy(() -> s.read("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.read("ab/../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(s.exists("../../etc/passwd")).isFalse();
    }

    @Test
    void extensionComesFromWhitelistOnly() {
        assertThat(AssetStore.extOf("shot.PNG", "image/png")).isEqualTo("png");
        assertThat(AssetStore.extOf(null, "application/pdf")).isEqualTo("pdf");
        assertThat(AssetStore.extOf("evil.exe", "application/octet-stream")).isEmpty();
        assertThat(AssetStore.extOf("noext", null)).isEmpty();
    }

    @Test
    void kindIsDerivedFromMimeFirst() {
        assertThat(AssetService.kindOf("image/jpeg", "x.bin")).isEqualTo("image");
        assertThat(AssetService.kindOf("audio/wav", null)).isEqualTo("audio");
        assertThat(AssetService.kindOf("video/mp4", null)).isEqualTo("video");
        // mime 缺失时才看后缀
        assertThat(AssetService.kindOf("application/octet-stream", "a.webp")).isEqualTo("image");
        assertThat(AssetService.kindOf("application/octet-stream", "a.pdf")).isEqualTo("document");
    }
}
