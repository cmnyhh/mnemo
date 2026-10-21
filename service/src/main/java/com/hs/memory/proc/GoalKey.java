package com.hs.memory.proc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 目标归一化：把「把 mnemo 部署到 2 核服务器上」和「部署 mnemo 到服务器」归成同一类任务。
 *
 * <p>做法是保守的字符级归一：去掉标点与空白、统一大小写、保留中文与字母数字。
 * 不引入分词或 embedding——聚类错了会把两条不相干的路径混成一份策略，
 * 而策略是要被真的执行的，这里的错误代价比「少聚一类」高得多。
 */
public final class GoalKey {

    /** 归一化后的最大长度。太长会让「同一件事换个说法」落到不同桶里。 */
    private static final int MAX_CHARS = 48;

    private GoalKey() {
    }

    public static String of(String goal) {
        if (goal == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean pendingSpace = false;
        for (int i = 0; i < goal.length(); i++) {
            char ch = goal.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                if (pendingSpace && sb.length() > 0) {
                    sb.append(' ');
                }
                pendingSpace = false;
                sb.append(Character.toLowerCase(ch));
            } else {
                // 标点、空白、emoji 都当分隔，但不连续插入空格
                pendingSpace = true;
            }
            if (sb.length() >= MAX_CHARS) {
                break;
            }
        }
        return sb.toString().trim();
    }

    /**
     * 技能名：ASCII 短标识，由 goal_key 的摘要生成。
     *
     * <p>不用 goal_key 本身：它含中文与空格，写进工具名或文件名会一路遇到转义问题。
     * 摘要只取 8 位，碰撞概率在单机几千条技能的量级上可以忽略；真撞了也只是
     * 名字重复，唯一约束在 (tenant, name, version) 上，不会串数据。
     */
    public static String slug(String goalKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest((goalKey == null ? "" : goalKey).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("proc-");
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 是 JDK 必备算法，这里不可能发生；退化成一个稳定但不唯一的名字
            return "proc-" + Integer.toHexString(String.valueOf(goalKey).hashCode());
        }
    }
}
