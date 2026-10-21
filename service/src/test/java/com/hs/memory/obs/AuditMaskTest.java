package com.hs.memory.obs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 审计里最危险的两件事：把密钥抄进去，以及因为多加了一个动作就显示成空白。
 */
class AuditMaskTest {

    @Test
    void masksValuesWhoseKeyLooksSecret() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("apiKey", "sk-live-123");
        detail.put("password", "hunter2");
        detail.put("newPassword", "hunter3");
        detail.put("access_token", "abc");
        detail.put("credentialId", "c-1");
        detail.put("keys", "embedding.model");
        detail.put("count", 3);

        Map<String, Object> masked = AuditService.mask(detail);

        assertThat(masked.get("apiKey")).isEqualTo("***");
        assertThat(masked.get("password")).isEqualTo("***");
        assertThat(masked.get("newPassword")).isEqualTo("***");
        assertThat(masked.get("access_token")).isEqualTo("***");
        assertThat(masked.get("credentialId")).isEqualTo("***");
        // 「keys」不是密钥本身，是被改动的字段名，不该打码
        assertThat(masked.get("keys")).isEqualTo("embedding.model");
        assertThat(masked.get("count")).isEqualTo(3);
    }

    @Test
    void emptyDetailIsAllowed() {
        assertThat(AuditService.mask(null)).isEmpty();
        assertThat(AuditService.mask(Map.of())).isEmpty();
    }

    @Test
    void knownActionsHaveChineseLabelsAndUnknownOnesPassThrough() {
        assertThat(AuditService.label("record.pin")).isEqualTo("钉住");
        assertThat(AuditService.label("config.update")).isEqualTo("改配置");
        // 新加动作忘了写标签时显示原文，总比显示空白强
        assertThat(AuditService.label("future.action")).isEqualTo("future.action");
    }
}
