package com.hs.memory.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuerySqueezeTest {

    @Test
    @DisplayName("疑问尾巴被去掉，名词短语保留")
    void dropsQuestionTail() {
        assertEquals("备份文件", QuerySqueeze.squeeze("备份文件放在哪里"));
        assertEquals("数据库备份文件", QuerySqueeze.squeeze("数据库备份文件放在哪个目录"));
        assertEquals("备份", QuerySqueeze.squeeze("备份放在哪"));
        assertEquals("配置文件", QuerySqueeze.squeeze("配置文件在哪里"));
    }

    @Test
    @DisplayName("请求套话被去掉")
    void dropsPoliteness() {
        assertEquals("备份", QuerySqueeze.squeeze("我只想知道备份"));
        assertEquals("配置", QuerySqueeze.squeeze("帮我看看配置"));
        assertEquals("端口", QuerySqueeze.squeeze("告知我端口是什么"));
    }

    @Test
    @DisplayName("句末语气词只削一次")
    void stripsTrailingParticleOnce() {
        assertEquals("是吗", QuerySqueeze.squeeze("是吗吗"));
        assertEquals("能用", QuerySqueeze.squeeze("能用吗"));
    }

    @Test
    @DisplayName("没有可删成分时原样返回，调用方据此判断不必重试")
    void keepsPlainPhrase() {
        assertEquals("备份", QuerySqueeze.squeeze("备份"));
        assertEquals("Qdrant 端口", QuerySqueeze.squeeze("Qdrant 端口"));
        assertFalse(QuerySqueeze.worthRetry("备份", QuerySqueeze.squeeze("备份")));
    }

    @Test
    @DisplayName("容器词不删：它们可能是实体的一部分")
    void keepsContainerWords() {
        assertEquals("配置文件", QuerySqueeze.squeeze("配置文件是什么"));
    }

    @Test
    @DisplayName("worthRetry 要求既有变化、又不短于两个字")
    void worthRetryNeedsChangeAndLength() {
        assertTrue(QuerySqueeze.worthRetry("备份文件放在哪里", "备份文件"));
        assertFalse(QuerySqueeze.worthRetry("备份", "备份"));
        assertFalse(QuerySqueeze.worthRetry("是什么", ""));
        assertFalse(QuerySqueeze.worthRetry("什么", ""));
    }

    @Test
    @DisplayName("长串优先：先删「应该放在」再删「哪个」，不留半截字")
    void longestPatternWins() {
        // 线上实测暴露过：先把「哪个」删掉会剩下「个路径下」，那是一条更差的查询。
        assertEquals("数据库备份文件 路径下",
                QuerySqueeze.squeeze("数据库备份文件应该放在哪个路径下"));
        assertEquals("备份文件", QuerySqueeze.squeeze("备份文件应该放在哪里"));
    }

    @Test
    @DisplayName("空输入不抛异常")
    void handlesNullAndEmpty() {
        assertEquals("", QuerySqueeze.squeeze(null));
        assertEquals("", QuerySqueeze.squeeze("   "));
        assertFalse(QuerySqueeze.worthRetry(null, ""));
    }
}
