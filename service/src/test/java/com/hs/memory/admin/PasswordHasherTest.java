package com.hs.memory.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    @Test
    void hashIsSaltedSoTheSamePasswordProducesDifferentStrings() {
        String a = PasswordHasher.hash("correct horse battery staple");
        String b = PasswordHasher.hash("correct horse battery staple");
        assertThat(a).isNotEqualTo(b);
        assertThat(a).startsWith("pbkdf2$210000$");
    }

    @Test
    void verifyAcceptsTheRightPasswordAndRejectsEverythingElse() {
        String hash = PasswordHasher.hash("s3cret-pass");
        assertThat(PasswordHasher.verify("s3cret-pass", hash)).isTrue();
        assertThat(PasswordHasher.verify("s3cret-pas", hash)).isFalse();
        assertThat(PasswordHasher.verify("", hash)).isFalse();
        assertThat(PasswordHasher.verify(null, hash)).isFalse();
    }

    @Test
    void malformedStoredValueFailsClosed() {
        assertThat(PasswordHasher.verify("x", "not-a-hash")).isFalse();
        assertThat(PasswordHasher.verify("x", "pbkdf2$abc$AAAA$BBBB")).isFalse();
        assertThat(PasswordHasher.verify("x", "pbkdf2$1000$!!!$@@@")).isFalse();
        assertThat(PasswordHasher.verify("x", null)).isFalse();
    }

    @Test
    void tokensAreLongRandomAndUrlSafe() {
        String t1 = PasswordHasher.token();
        String t2 = PasswordHasher.token();
        assertThat(t1).isNotEqualTo(t2);
        assertThat(t1).hasSizeGreaterThanOrEqualTo(40);
        assertThat(t1).matches("[A-Za-z0-9_-]+");
    }
}
