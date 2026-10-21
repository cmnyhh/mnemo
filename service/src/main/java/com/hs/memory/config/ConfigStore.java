package com.hs.memory.config;

import java.time.Instant;
import java.util.Map;

/**
 * 配置的持久化端口。
 *
 * <p>做成接口不是为了「将来换存储」，而是为了让合并/校验这些真正容易出错的逻辑
 * 能脱离数据库被单测覆盖——配置错了会让整个服务静默降级，它值得有测试。
 */
public interface ConfigStore {

    Stored load();

    /**
     * 写入覆盖值。
     * @param values           要持久化的完整覆盖集合
     * @param expectedRevision 期望的版本号；不匹配说明有人在别处改过，返回 false 由上层报 409
     * @return true 表示写入成功
     */
    boolean save(Map<String, Object> values, long expectedRevision);

    /** 读一段与配置无关的运行时状态（例如最近一次重建索引用的向量指纹）。 */
    String state(String key);

    void putState(String key, String value);

    record Stored(long revision, Map<String, Object> values, Instant updatedAt) {
    }
}
