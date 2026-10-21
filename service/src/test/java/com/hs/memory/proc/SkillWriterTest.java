package com.hs.memory.proc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SkillWriterTest {

    private static Distiller.Candidate candidate() {
        return new Distiller.Candidate("proc-1a2b3c4d", "把 mnemo 部署到 2 核服务器", "把 mnemo 部署到 2 核服务器",
                List.of(new Distiller.Step(1, "读 .env.example", "先看默认值", "看到 8090"),
                        new Distiller.Step(2, "改端口后启动", "因为 8090 被占用，所以改成 8091", "readyz 返回 200")),
                List.of("「直接 docker compose up」端口冲突"),
                4, 0.75, 0.8, false, null);
    }

    @Test
    void emitsFrontmatterAndReadableSteps() {
        String md = SkillWriter.render("proc-1a2b3c4d", "部署 mnemo", 2, candidate());

        assertThat(md).startsWith("---\n");
        assertThat(md).contains("name: proc-1a2b3c4d");
        assertThat(md).contains("version: 2");
        assertThat(md).contains("# 部署 mnemo");
        assertThat(md).contains("## 何时使用");
        assertThat(md).contains("1. **读 .env.example**");
        assertThat(md).contains("为什么：先看默认值");
        assertThat(md).contains("预期：看到 8090");
        assertThat(md).contains("## 常见坑");
        assertThat(md).contains("## 证据");
        assertThat(md).contains("75%");
    }

    @Test
    void quotesInDescriptionCannotBreakFrontmatter() {
        var c = new Distiller.Candidate("proc-1a2b3c4d", "修 \"502\" 报错", "修 502",
                List.of(new Distiller.Step(1, "查日志", null, null)), List.of(), 2, 1.0, 1.0, false, null);

        String md = SkillWriter.render("proc-1a2b3c4d", "修 502", 1, c);
        String descriptionLine = md.lines().filter(l -> l.startsWith("description:")).findFirst().orElseThrow();

        assertThat(descriptionLine).doesNotContain("\"502\"");
        assertThat(descriptionLine).contains("'502'");
    }
}
