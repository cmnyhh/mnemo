package com.hs.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.stereotype.Component;

/**
 * 启动期守卫：每个 Spring 组件的 public 构造器要么只有一个，要么有一个标了 {@code @Autowired}。
 *
 * <p>这条规则值得单独立一个测试，因为它的违约方式很隐蔽——单测全绿、编译通过，
 * 只有容器真正启动时才炸 {@code No default constructor found}。本轮就踩过一次：
 * 给存储类加了一个测试用的构造器，Spring 立刻不知道选哪个，线上直接起不来。
 */
class ComponentConstructorsTest {

    @Test
    void everyComponentIsUnambiguousForSpring() throws Exception {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        // Component 是 @Service / @Repository / @RestController 的元注解，一个过滤器就够
        provider.addIncludeFilter(
                new org.springframework.core.type.filter.AnnotationTypeFilter(Component.class));

        List<String> ambiguous = new ArrayList<>();
        int scanned = 0;
        for (BeanDefinition bd : provider.findCandidateComponents("com.hs.memory")) {
            scanned++;
            Class<?> type = Class.forName(bd.getBeanClassName());
            Constructor<?>[] publicOnes = type.getConstructors();
            if (publicOnes.length <= 1) {
                continue;
            }
            boolean marked = false;
            for (Constructor<?> c : publicOnes) {
                if (c.isAnnotationPresent(Autowired.class)) {
                    marked = true;
                    break;
                }
            }
            if (!marked) {
                ambiguous.add(type.getSimpleName() + "（" + publicOnes.length + " 个构造器）");
            }
        }
        // 扫描本身也要断言：扫不到任何组件时上面那个 isEmpty 会假绿
        assertThat(scanned).as("组件扫描没生效，这条守卫等于没写").isGreaterThan(10);
        assertThat(ambiguous)
                .as("多个 public 构造器时必须有一个标 @Autowired，否则 Spring 启动失败")
                .isEmpty();
    }
}
