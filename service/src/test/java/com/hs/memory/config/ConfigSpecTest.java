package com.hs.memory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hs.memory.TestSettings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigSpecTest {

    private final ConfigSpec spec = new ConfigSpec(TestSettings.of());

    @Test
    void baselineComesFromEnvironmentAndOverrideReplacesIt() {
        Map<String, Object> merged = spec.merge(Map.of(
                ConfigSpec.EMBEDDING_PROVIDER, "http",
                ConfigSpec.EMBEDDING_BASE_URL, "http://10.0.0.5:9997/v1"));
        assertThat(ConfigSpec.str(merged, ConfigSpec.EMBEDDING_PROVIDER)).isEqualTo("http");
        assertThat(ConfigSpec.str(merged, ConfigSpec.EMBEDDING_BASE_URL)).isEqualTo("http://10.0.0.5:9997/v1");
        // 没覆盖的项仍是基线
        assertThat(ConfigSpec.intOf(merged, ConfigSpec.EMBEDDING_DIMS)).isEqualTo(4096);
    }

    @Test
    void unknownKeyIsRejectedInsteadOfSilentlyStored() {
        assertThatThrownBy(() -> spec.coerce(Map.of("embedding.magic", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知配置项");
    }

    @Test
    void numericRangeIsEnforcedBeforeSaving() {
        assertThatThrownBy(() -> spec.coerce(Map.of(ConfigSpec.EMBEDDING_DIMS, 70000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能大于");
        assertThatThrownBy(() -> spec.coerce(Map.of(ConfigSpec.ABSTAIN_THRESHOLD, 1.5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void httpWithoutBaseUrlIsRejected() {
        Map<String, Object> merged = spec.merge(Map.of(ConfigSpec.EMBEDDING_PROVIDER, "http"));
        assertThatThrownBy(() -> spec.validate(merged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("服务地址");
    }

    @Test
    void defaultTopKCannotExceedMaxTopK() {
        Map<String, Object> merged = spec.merge(Map.of(
                ConfigSpec.EMBEDDING_PROVIDER, "hash",
                ConfigSpec.DEFAULT_TOP_K, 30,
                ConfigSpec.MAX_TOP_K, 20));
        assertThatThrownBy(() -> spec.validate(merged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("默认返回条数");
    }

    @Test
    void formMetadataCoversEveryKeyAndNeverLeaksTheSecret() {
        Map<String, Object> effective = spec.merge(Map.of(
                ConfigSpec.EMBEDDING_API_KEY, "sk-abcdef1234567890"));
        List<ConfigField> fields = spec.fields(effective);
        assertThat(fields).hasSize(spec.keys().size());
        assertThat(fields).extracting(ConfigField::key).containsExactlyInAnyOrderElementsOf(spec.keys());

        ConfigField secret = fields.stream()
                .filter(f -> f.key().equals(ConfigSpec.EMBEDDING_API_KEY))
                .findFirst().orElseThrow();
        assertThat(secret.value()).isNull();
        assertThat(secret.masked()).isEqualTo("****7890");
        assertThat(secret.type()).isEqualTo(ConfigField.Type.SECRET);
    }

    @Test
    void integerAndDoubleFieldsAcceptJsonNumbersAsStrings() {
        Map<String, Object> typed = spec.coerce(Map.of(
                ConfigSpec.DENSE_TOP_K, "25",
                ConfigSpec.DENSE_MIN_SCORE, "0.42"));
        assertThat(typed.get(ConfigSpec.DENSE_TOP_K)).isEqualTo(25);
        assertThat(typed.get(ConfigSpec.DENSE_MIN_SCORE)).isEqualTo(0.42);
    }

    @Test
    void baseUrlMustLookLikeAUrl() {
        assertThatThrownBy(() -> spec.coerce(Map.of(ConfigSpec.EMBEDDING_BASE_URL, "10.0.0.5:9997")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
    }

    @Test
    void rerankDefaultsToNoneSoNothingBreaksWhenUnconfigured() {
        Map<String, Object> merged = spec.merge(Map.of());
        assertThat(ConfigSpec.str(merged, ConfigSpec.RERANK_PROVIDER)).isEqualTo("none");
        assertThat(ConfigSpec.intOf(merged, ConfigSpec.RERANK_TOP_N)).isEqualTo(12);
        spec.validate(merged);
    }

    @Test
    void rerankHttpRequiresBaseUrlAndModel() {
        Map<String, Object> noUrl = spec.merge(Map.of(ConfigSpec.RERANK_PROVIDER, "http"));
        assertThatThrownBy(() -> spec.validate(noUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("服务地址");

        Map<String, Object> noModel = spec.merge(Map.of(
                ConfigSpec.RERANK_PROVIDER, "http",
                ConfigSpec.RERANK_BASE_URL, "https://api.example.com/v1"));
        assertThatThrownBy(() -> spec.validate(noModel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型名");
    }

    @Test
    void rerankBaseUrlIsValidatedLikeEmbedding() {
        assertThatThrownBy(() -> spec.coerce(Map.of(ConfigSpec.RERANK_BASE_URL, "api.example.com/v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
    }

    @Test
    void rerankFieldsAppearInTheFormUnderTheirOwnGroup() {
        List<ConfigField> fields = spec.fields(spec.merge(Map.of()));
        long rerankFields = fields.stream().filter(f -> "rerank".equals(f.group())).count();
        assertThat(rerankFields).isEqualTo(7);
    }
}
