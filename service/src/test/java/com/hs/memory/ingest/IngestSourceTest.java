package com.hs.memory.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hs.memory.api.IngestRequest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 来源标签的解析规则。
 *
 * <p>这一组断言存在的理由：线上曾经把 trigger=explicit 一律改写成 manual，
 * 于是 memory_write 写的东西在库里和人工录入完全一样，排查「模型到底写了多少」
 * 时得出过完全错误的结论（把 531 条外部批量写入算成了模型主动写）。
 */
class IngestSourceTest {

    private static IngestRequest.Turn turn(String trigger) {
        return new IngestRequest.Turn("s1", 1, List.of(), trigger);
    }

    @Test
    @DisplayName("调用方自报 tool 就落 tool，explicit 不能把它改写成 manual")
    void keepsDeclaredToolEvenWhenExplicit() {
        assertEquals("tool", IngestService.resolveSource("tool", turn("explicit")));
    }

    @Test
    @DisplayName("自报值统一转小写")
    void lowercasesDeclaredValue() {
        assertEquals("tool", IngestService.resolveSource("TOOL", null));
        assertEquals("import", IngestService.resolveSource("Import", turn("explicit")));
    }

    @Test
    @DisplayName("不自报时：带 turn 算对话轮次，不带才算人工")
    void fallsBackToShapeOfRequest() {
        assertEquals("turn", IngestService.resolveSource(null, turn("explicit")));
        assertEquals("turn", IngestService.resolveSource("  ", turn(null)));
        assertEquals("manual", IngestService.resolveSource(null, null));
    }
}
