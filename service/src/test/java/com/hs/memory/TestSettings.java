package com.hs.memory;

/** 测试用的基线配置，集中一处避免每个测试都写三十多个参数。 */
public final class TestSettings {

    private TestSettings() {
    }

    public static Settings of() {
        return with("hash", "", "", "qwen3-embedding-8b", 4096);
    }

    public static Settings with(String provider, String baseUrl, String apiKey, String model, int dims) {
        return new Settings(
                "default",
                "jdbc:postgresql://127.0.0.1:5432/memory", "memory", "memory",
                "http://127.0.0.1:6333", "", "memory", 3000, true,
                provider, baseUrl, apiKey, model, dims, 3000,
                40, 40, 60, 6, 50, 0.35, 1200, 0.25, 1, 6,
                "none", "", "", "", 600, 12, 0.35,
                "none", "", "", "", 20000, 8, 0.35,
                24, 180, 2, 900,
                "/tmp/dsh-memory-assets", 20971520,
                "none", "", "", "", 30000,
                true, 0.75, 0.4, 2, 0.5, 3, 0.6,
                true, "memory_app");
    }

    /** 打开精排的基线（默认指向一个不存在的端口，测试里不会真去调）。 */
    public static Settings withRerank() {
        return new Settings(
                "default",
                "jdbc:postgresql://127.0.0.1:5432/memory", "memory", "memory",
                "http://127.0.0.1:6333", "", "memory", 3000, true,
                "hash", "", "", "qwen3-embedding-8b", 4096, 3000,
                40, 40, 60, 6, 50, 0.35, 1200, 0.25, 1, 6,
                "http", "http://127.0.0.1:9/v1", "", "test-reranker", 200, 12, 0.35,
                "none", "", "", "", 20000, 8, 0.35,
                24, 180, 2, 900,
                "/tmp/dsh-memory-assets", 20971520,
                "none", "", "", "", 30000,
                true, 0.75, 0.4, 2, 0.5, 3, 0.6,
                true, "memory_app");
    }

    /** 打开抽取的基线（同样指向不存在的端口，只有显式调用才会失败）。 */
    public static Settings withExtract() {
        return new Settings(
                "default",
                "jdbc:postgresql://127.0.0.1:5432/memory", "memory", "memory",
                "http://127.0.0.1:6333", "", "memory", 3000, true,
                "hash", "", "", "qwen3-embedding-8b", 4096, 3000,
                40, 40, 60, 6, 50, 0.35, 1200, 0.25, 1, 6,
                "none", "", "", "", 600, 12, 0.35,
                "http", "http://127.0.0.1:9/v1", "", "test-extractor", 2000, 8, 0.35,
                24, 180, 2, 900,
                "/tmp/dsh-memory-assets", 20971520,
                "none", "", "", "", 30000,
                true, 0.75, 0.4, 2, 0.5, 3, 0.6,
                true, "memory_app");
    }
}
