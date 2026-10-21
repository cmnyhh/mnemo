package com.hs.memory.admin;

/** 控制台账号的持久化端口（单行、单账号）。 */
public interface CredentialStore {

    /** @return 已保存的账号；尚未初始化时返回 null（字段可能为空） */
    Credential load();

    void save(String username, String hash);

    record Credential(String username, String passwordHash) {
    }
}
