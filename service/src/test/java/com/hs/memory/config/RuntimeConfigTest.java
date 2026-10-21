package com.hs.memory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hs.memory.TestSettings;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeConfigTest {

    @Test
    void changingTheProviderMarksTheVectorSpaceDirtyUntilReindexed() {
        FakeStore store = new FakeStore();
        RuntimeConfig runtime = new RuntimeConfig(TestSettings.of(), store);

        RuntimeConfig.UpdateResult result = runtime.update(Map.of(
                ConfigSpec.EMBEDDING_PROVIDER, "http",
                ConfigSpec.EMBEDDING_BASE_URL, "http://10.0.0.5:9997/v1"), 0);

        assertThat(result.spaceChanged()).isTrue();
        assertThat(result.requiresReindex()).isTrue();
        assertThat(runtime.snapshot().embeddingIsHttp()).isTrue();
        assertThat(runtime.requiresReindex()).isTrue();
        assertThat(runtime.revision()).isEqualTo(1);

        runtime.markReindexed();
        assertThat(runtime.requiresReindex()).isFalse();

        // 只改检索参数不该让向量失效
        RuntimeConfig.UpdateResult tuning = runtime.update(Map.of(ConfigSpec.DENSE_MIN_SCORE, 0.31), 1);
        assertThat(tuning.spaceChanged()).isFalse();
        assertThat(runtime.requiresReindex()).isFalse();
        assertThat(runtime.snapshot().denseMinScore()).isEqualTo(0.31);
    }

    @Test
    void changingDimensionsIsAVectorChangeEvenIfTheProviderDoesNotMove() {
        FakeStore store = new FakeStore();
        RuntimeConfig runtime = new RuntimeConfig(TestSettings.of(), store);
        runtime.markReindexed();

        RuntimeConfig.UpdateResult result = runtime.update(Map.of(ConfigSpec.EMBEDDING_DIMS, 1024), 0);
        assertThat(result.spaceChanged()).isTrue();
        assertThat(runtime.requiresReindex()).isTrue();
        assertThat(runtime.snapshot().spaceId()).isEqualTo("qwen3-embedding-8b_1024");
    }

    @Test
    void aNullPatchValueDropsTheOverrideAndFallsBackToTheEnvironment() {
        FakeStore store = new FakeStore();
        RuntimeConfig runtime = new RuntimeConfig(TestSettings.of(), store);
        runtime.update(Map.of(ConfigSpec.DEFAULT_TOP_K, 12), 0);
        assertThat(runtime.snapshot().defaultTopK()).isEqualTo(12);

        Map<String, Object> reset = new HashMap<>();
        reset.put(ConfigSpec.DEFAULT_TOP_K, null);
        runtime.update(reset, 1);
        assertThat(runtime.snapshot().defaultTopK()).isEqualTo(6);
        assertThat(store.values).doesNotContainKey(ConfigSpec.DEFAULT_TOP_K);
    }

    @Test
    void staleRevisionIsRejectedInsteadOfOverwritingAnotherTab() {
        FakeStore store = new FakeStore();
        RuntimeConfig runtime = new RuntimeConfig(TestSettings.of(), store);
        runtime.update(Map.of(ConfigSpec.DEFAULT_TOP_K, 10), 0);

        assertThatThrownBy(() -> runtime.update(Map.of(ConfigSpec.DEFAULT_TOP_K, 20), 0))
                .isInstanceOf(RuntimeConfig.RevisionConflictException.class);
        assertThat(runtime.snapshot().defaultTopK()).isEqualTo(10);
    }

    @Test
    void invalidCombinationIsRejectedBeforeItReachesTheDatabase() {
        FakeStore store = new FakeStore();
        RuntimeConfig runtime = new RuntimeConfig(TestSettings.of(), store);

        assertThatThrownBy(() -> runtime.update(Map.of(ConfigSpec.EMBEDDING_PROVIDER, "http"), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("服务地址");
        assertThat(store.values).isEmpty();
        assertThat(runtime.snapshot().embeddingProvider()).isEqualTo("hash");
    }

    @Test
    void embeddingClientFollowsTheCurrentConfiguration() {
        FakeStore store = new FakeStore();
        RuntimeConfig runtime = new RuntimeConfig(TestSettings.of(), store);
        assertThat(runtime.embedding()).isInstanceOf(com.hs.memory.embed.HashEmbeddingClient.class);

        runtime.update(Map.of(
                ConfigSpec.EMBEDDING_PROVIDER, "http",
                ConfigSpec.EMBEDDING_BASE_URL, "http://10.0.0.5:9997/v1",
                ConfigSpec.EMBEDDING_MODEL, "qwen3-embedding-0.6b",
                ConfigSpec.EMBEDDING_DIMS, 1024), 0);

        assertThat(runtime.embedding()).isInstanceOf(com.hs.memory.embed.HttpEmbeddingClient.class);
        assertThat(runtime.embedding().dims()).isEqualTo(1024);
        assertThat(runtime.embedding().model()).isEqualTo("qwen3-embedding-0.6b");
    }

    /** 内存版 ConfigStore：把版本号语义也实现出来，才能测到 409 那条路径。 */
    private static final class FakeStore implements ConfigStore {
        private Map<String, Object> values = new HashMap<>();
        private final Map<String, String> states = new HashMap<>();
        private long revision;
        private Instant updatedAt = Instant.now();

        @Override
        public Stored load() {
            return new Stored(revision, Map.copyOf(values), updatedAt);
        }

        @Override
        public boolean save(Map<String, Object> next, long expectedRevision) {
            if (expectedRevision != revision) {
                return false;
            }
            this.values = new HashMap<>(next);
            this.revision += 1;
            this.updatedAt = Instant.now();
            return true;
        }

        @Override
        public String state(String key) {
            return states.get(key);
        }

        @Override
        public void putState(String key, String value) {
            states.put(key, value);
        }
    }
}
