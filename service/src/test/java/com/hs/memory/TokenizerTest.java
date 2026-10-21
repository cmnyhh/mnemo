package com.hs.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.hs.memory.embed.Tokenizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenizerTest {

    @Test
    void splitsChineseIntoBigrams() {
        List<String> tokens = Tokenizer.tokenize("部署端口");
        assertThat(tokens).containsExactly("部署", "署端", "端口");
    }

    @Test
    void keepsSingleChineseChar() {
        assertThat(Tokenizer.tokenize("跑")).containsExactly("跑");
    }

    @Test
    void keepsIpAndPortAsTokens() {
        List<String> tokens = Tokenizer.tokenize("服务监听 10.20.0.9:8080");
        assertThat(tokens).contains("10.20.0.9:8080");
    }

    @Test
    void mixesLatinAndChinese() {
        List<String> tokens = Tokenizer.tokenize("kb-service 用 8080 端口");
        assertThat(tokens).contains("kb-service", "8080", "端口");
    }

    @Test
    void searchTextIsSpaceSeparated() {
        String text = Tokenizer.searchText("记忆服务跑在四核机器上", "端口 8080");
        assertThat(text).contains(" ").doesNotContain("记忆服务跑在");
        assertThat(text.split(" ")).contains("记忆", "8080");
    }

    @Test
    void emptyInputYieldsEmpty() {
        assertThat(Tokenizer.tokenize("")).isEmpty();
        assertThat(Tokenizer.tokenize(null)).isEmpty();
    }
}
