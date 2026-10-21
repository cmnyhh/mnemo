package com.hs.memory.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 令牌的纯逻辑与参数校验。数据库那半（插入、按哈希查、撤销）需要真 PG，
 * 由部署后的烟测覆盖；这里守住「明文不出服务端」「过期与撤销必须失效」这些不能靠人工发现的点。
 */
class TokenServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TokenService service = new TokenService(jdbc);

    @Test
    void mint_hasPrefixLengthAndNoAmbiguousChars() {
        String token = TokenService.mint();
        assertThat(token).startsWith(TokenService.PREFIX);
        // mn_ + 43 位 base64url（32 字节无填充）
        assertThat(token).hasSize(TokenService.PREFIX.length() + 43);
        assertThat(token.substring(TokenService.PREFIX.length()))
                .matches("[A-Za-z0-9_-]+");
    }

    @Test
    void mint_isRandomEnough() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            assertThat(seen.add(TokenService.mint())).as("生成的令牌出现重复").isTrue();
        }
    }

    @Test
    void sha256_matchesKnownVectors() {
        assertThat(TokenService.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(TokenService.sha256Hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void sha256_differsForDifferentInput() {
        assertThat(TokenService.sha256Hex("mn_aaa")).isNotEqualTo(TokenService.sha256Hex("mn_aab"));
    }

    @Test
    void prefix_truncatesLongTokensAndSurvivesShortOnes() {
        String token = TokenService.mint();
        assertThat(TokenService.prefixOf(token)).hasSize(11).isEqualTo(token.substring(0, 11));
        assertThat(TokenService.prefixOf("short")).isEqualTo("short");
        assertThat(TokenService.prefixOf(null)).isEmpty();
    }

    @Test
    void row_isActiveOnlyWhenNeitherRevokedNorExpired() {
        Instant now = Instant.parse("2026-10-21T00:00:00Z");
        Instant past = now.minus(1, ChronoUnit.DAYS);
        Instant future = now.plus(1, ChronoUnit.DAYS);

        assertThat(row(future, null).active(now)).isTrue();
        assertThat(row(future, null).state(now)).isEqualTo("active");
        // 永不过期
        assertThat(row(null, null).active(now)).isTrue();
        // 已过期
        assertThat(row(past, null).active(now)).isFalse();
        assertThat(row(past, null).state(now)).isEqualTo("expired");
        // 已撤销：即使还没到期也不许用
        assertThat(row(future, past).active(now)).isFalse();
        assertThat(row(future, past).state(now)).isEqualTo("revoked");
        // 撤销优先于过期
        assertThat(row(past, past).state(now)).isEqualTo("revoked");
    }

    @Test
    void view_carriesNoSecret() {
        Instant now = Instant.parse("2026-10-21T00:00:00Z");
        Map<String, Object> view = row(now.plus(1, ChronoUnit.DAYS), null).view(now);
        assertThat(view).containsKeys("id", "name", "prefix", "state", "createdAt", "expiresAt");
        assertThat(view).doesNotContainKeys("token", "tokenHash", "hash");
        // 前缀可以露，但只能是前缀：43 位密钥本体不在任何字段里
        assertThat(String.valueOf(view.get("prefix"))).hasSizeLessThanOrEqualTo(11);
    }

    @Test
    void create_rejectsBadNameAndWindow() {
        assertThatThrownBy(() -> service.create("  ", null, "default", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("名称");
        assertThatThrownBy(() -> service.create("x".repeat(65), null, "default", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
        assertThatThrownBy(() -> service.create("ok", 0, "default", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1–3650");
        assertThatThrownBy(() -> service.create("ok", 3651, "default", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1–3650");
    }

    @Test
    void verify_ignoresBlankInputWithoutTouchingTheDatabase() {
        assertThat(service.verify(null)).isEmpty();
        assertThat(service.verify("   ")).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    private static TokenService.TokenRow row(Instant expiresAt, Instant revokedAt) {
        return new TokenService.TokenRow(UUID.randomUUID(), "插件", "mn_abcdefgh",
                "default", Instant.parse("2026-10-01T00:00:00Z"), null, expiresAt, revokedAt);
    }
}
