package com.hs.memory.config;

import java.util.List;

/**
 * 一个可编辑配置项的元数据。
 *
 * <p>元数据由服务端下发，控制台照着渲染表单——这样「界面上能改什么」与「服务端接受什么」
 * 只有一份定义。少一份定义就少一处漂移：界面上加了一个框、后端不认，是这类控制台
 * 最常见的失效方式。
 *
 * @param key        扁平键（如 {@code embedding.model}），存储与 API 都用它
 * @param group      分组（embedding / search），决定表单分区
 * @param label      中文标签
 * @param type       值类型，决定输入控件与校验
 * @param options    枚举可选值，非枚举为空
 * @param value      当前生效值（密钥类为 null）
 * @param masked     密钥类的掩码展示
 * @param fallback   环境变量给出的基线值（用于「恢复默认」与解释来源）
 * @param min        数值下限
 * @param max        数值上限
 * @param help       一句话说明，直接显示在输入框下面
 * @param placeholder 输入提示
 * @param secret     是否密钥（读回时掩码，写回时留空表示不改）
 * @param restart    改动是否必须重启才生效（本服务目前全部热生效，保留字段给未来）
 */
public record ConfigField(
        String key,
        String group,
        String label,
        Type type,
        List<String> options,
        Object value,
        String masked,
        Object fallback,
        Double min,
        Double max,
        String help,
        String placeholder,
        boolean secret,
        boolean restart,
        boolean affectsVectors) {

    public enum Type {
        ENUM, INT, DOUBLE, STRING, SECRET
    }

    /** 密钥只露尾四位：够确认「是不是那一把」，不够拿去用。 */
    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        if (secret.length() <= 4) {
            return "****";
        }
        return "****" + secret.substring(secret.length() - 4);
    }

    public static ConfigField enumField(String key, String group, String label, List<String> options,
                                        String value, String fallback, String help) {
        return new ConfigField(key, group, label, Type.ENUM, options, value, null, fallback,
                null, null, help, null, false, false, false);
    }

    public static ConfigField intField(String key, String group, String label, int value, int fallback,
                                       int min, int max, String help, String placeholder) {
        return new ConfigField(key, group, label, Type.INT, List.of(), value, null, fallback,
                (double) min, (double) max, help, placeholder, false, false, false);
    }

    public static ConfigField doubleField(String key, String group, String label, double value, double fallback,
                                          double min, double max, String help) {
        return new ConfigField(key, group, label, Type.DOUBLE, List.of(), value, null, fallback,
                min, max, help, null, false, false, false);
    }

    public static ConfigField stringField(String key, String group, String label, String value, String fallback,
                                          String help, String placeholder) {
        return new ConfigField(key, group, label, Type.STRING, List.of(), value, null, fallback,
                null, null, help, placeholder, false, false, false);
    }

    public static ConfigField secretField(String key, String group, String label, String value, String help,
                                          String placeholder) {
        return new ConfigField(key, group, label, Type.SECRET, List.of(), null, mask(value),
                null, null, null, help, placeholder, true, false, false);
    }
}
