package com.hs.memory.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AdminAuthServiceTest {

    private static AdminSettings settings(String username, String password) {
        return new AdminSettings(username, password, 12, "auto");
    }

    @Test
    void withoutAnyPasswordTheConsoleStaysDisabled() {
        AdminAuthService auth = new AdminAuthService(settings("admin", ""), new FakeStore());
        auth.bootstrap();

        assertThat(auth.enabled()).isFalse();
        assertThat(auth.login("admin", "anything", "127.0.0.1").status())
                .isEqualTo(AdminAuthService.LoginStatus.DISABLED);
    }

    @Test
    void environmentCredentialsSeedTheStoreOnceAndThenTheDatabaseWins() {
        FakeStore store = new FakeStore();
        AdminAuthService auth = new AdminAuthService(settings("alice", "first-pass"), store);
        auth.bootstrap();

        assertThat(auth.enabled()).isTrue();
        assertThat(auth.username()).isEqualTo("alice");
        assertThat(store.username).isEqualTo("alice");
        assertThat(store.hash).isNotNull();

        AdminAuthService.LoginResult ok = auth.login("alice", "first-pass", "127.0.0.1");
        assertThat(ok.status()).isEqualTo(AdminAuthService.LoginStatus.OK);
        assertThat(auth.authenticate(ok.session().token())).isPresent();

        // 换一个进程、环境变量还是旧值：库里已有凭证，不该被重新覆盖
        AdminAuthService second = new AdminAuthService(settings("bob", "ignored"), store);
        second.bootstrap();
        assertThat(second.username()).isEqualTo("alice");
        assertThat(second.login("alice", "first-pass", "127.0.0.1").status())
                .isEqualTo(AdminAuthService.LoginStatus.OK);
        assertThat(second.login("bob", "ignored", "127.0.0.1").status())
                .isEqualTo(AdminAuthService.LoginStatus.BAD_CREDENTIALS);
    }

    @Test
    void theUsernameMustMatchAndIsCaseInsensitive() {
        AdminAuthService auth = new AdminAuthService(settings("Alice", "right-pass"), new FakeStore());
        auth.bootstrap();

        assertThat(auth.login("alice", "right-pass", "127.0.0.1").status())
                .isEqualTo(AdminAuthService.LoginStatus.OK);
        assertThat(auth.login("Alice", "right-pass", "127.0.0.1").status())
                .isEqualTo(AdminAuthService.LoginStatus.OK);

        // 口令对、账号错：与口令错给同一个结果，不泄露账号是否存在
        AdminAuthService.LoginResult wrongUser = auth.login("someone", "right-pass", "127.0.0.1");
        assertThat(wrongUser.status()).isEqualTo(AdminAuthService.LoginStatus.BAD_CREDENTIALS);
        assertThat(wrongUser.message()).isEqualTo("账号或口令不正确");
        assertThat(auth.login("alice", "wrong-pass", "127.0.0.1").message())
                .isEqualTo("账号或口令不正确");
    }

    @Test
    void blankUsernameNeverMatches() {
        AdminAuthService auth = new AdminAuthService(settings("admin", "right-pass"), new FakeStore());
        auth.bootstrap();

        assertThat(auth.login(null, "right-pass", "127.0.0.1").status())
                .isEqualTo(AdminAuthService.LoginStatus.BAD_CREDENTIALS);
        assertThat(auth.login("", "right-pass", "127.0.0.1").status())
                .isEqualTo(AdminAuthService.LoginStatus.BAD_CREDENTIALS);
    }

    @Test
    void fiveFailuresThenThrottledWithARetryHint() {
        AdminAuthService auth = new AdminAuthService(settings("admin", "right-pass"), new FakeStore());
        auth.bootstrap();

        for (int i = 0; i < 5; i++) {
            assertThat(auth.login("admin", "wrong", "10.0.0.9").status())
                    .isEqualTo(AdminAuthService.LoginStatus.BAD_CREDENTIALS);
        }
        AdminAuthService.LoginResult blocked = auth.login("admin", "right-pass", "10.0.0.9");
        assertThat(blocked.status()).isEqualTo(AdminAuthService.LoginStatus.THROTTLED);
        assertThat(blocked.retryAfterSec()).isPositive();

        // 另一个来源不受影响：限流是按 IP 的，不是全局锁
        assertThat(auth.login("admin", "right-pass", "10.0.0.10").status())
                .isEqualTo(AdminAuthService.LoginStatus.OK);
    }

    @Test
    void changingThePasswordInvalidatesEverySession() {
        AdminAuthService auth = new AdminAuthService(settings("admin", "old-pass-1"), new FakeStore());
        auth.bootstrap();
        AdminAuthService.Session session = auth.login("admin", "old-pass-1", "127.0.0.1").session();

        assertThatThrownBy(() -> auth.changeCredentials("wrong", null, "new-pass-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("原口令");
        assertThatThrownBy(() -> auth.changeCredentials("old-pass-1", null, "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少");

        auth.changeCredentials("old-pass-1", null, "new-pass-1");
        assertThat(auth.authenticate(session.token())).isEmpty();
        assertThat(auth.login("admin", "old-pass-1", "127.0.0.1").status())
                .isEqualTo(AdminAuthService.LoginStatus.BAD_CREDENTIALS);
        assertThat(auth.login("admin", "new-pass-1", "127.0.0.1").status())
                .isEqualTo(AdminAuthService.LoginStatus.OK);
    }

    @Test
    void renamingTheAccountKeepsThePasswordAndDropsOldSessions() {
        FakeStore store = new FakeStore();
        AdminAuthService auth = new AdminAuthService(settings("admin", "pass-12345"), store);
        auth.bootstrap();
        AdminAuthService.Session session = auth.login("admin", "pass-12345", "127.0.0.1").session();

        auth.changeCredentials("pass-12345", "lyk", "");

        assertThat(auth.username()).isEqualTo("lyk");
        assertThat(store.username).isEqualTo("lyk");
        assertThat(auth.authenticate(session.token())).isEmpty();
        assertThat(auth.login("admin", "pass-12345", "127.0.0.1").status())
                .isEqualTo(AdminAuthService.LoginStatus.BAD_CREDENTIALS);
        assertThat(auth.login("lyk", "pass-12345", "127.0.0.1").status())
                .isEqualTo(AdminAuthService.LoginStatus.OK);
    }

    @Test
    void aNoOpOrMalformedChangeIsRejected() {
        AdminAuthService auth = new AdminAuthService(settings("admin", "pass-12345"), new FakeStore());
        auth.bootstrap();

        assertThatThrownBy(() -> auth.changeCredentials("pass-12345", "admin", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有变化");
        assertThatThrownBy(() -> auth.changeCredentials("pass-12345", "a", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3–32");
        assertThatThrownBy(() -> auth.changeCredentials("pass-12345", "有中文", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3–32");
    }

    @Test
    void logoutDropsTheSessionImmediately() {
        AdminAuthService auth = new AdminAuthService(settings("admin", "pass-12345"), new FakeStore());
        auth.bootstrap();
        AdminAuthService.Session session = auth.login("admin", "pass-12345", "127.0.0.1").session();

        auth.logout(session.token());
        assertThat(auth.authenticate(session.token())).isEmpty();
        assertThat(auth.authenticate(null)).isEmpty();
        assertThat(auth.authenticate("made-up")).isEmpty();
    }

    private static final class FakeStore implements CredentialStore {
        private String username;
        private String hash;

        @Override
        public Credential load() {
            return hash == null ? null : new Credential(username == null ? "admin" : username, hash);
        }

        @Override
        public void save(String username, String hash) {
            this.username = username;
            this.hash = hash;
        }
    }
}
