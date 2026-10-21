package com.hs.memory;

/**
 * 全部可变配置集中在这里，构造由 {@link SettingsConfig} 从 application.yml（也就是环境变量）注入。
 * 用 record + 显式 @Value 而不是 @ConfigurationProperties：少一层绑定，缺 key 时启动直接报错。
 */
public record Settings(
        String tenant,
        String dbUrl,
        String dbUser,
        String dbPassword,
        String qdrantUrl,
        String qdrantApiKey,
        String qdrantCollection,
        int qdrantTimeoutMs,
        boolean qdrantWait,
        String embeddingProvider,
        String embeddingBaseUrl,
        String embeddingApiKey,
        String embeddingModel,
        int embeddingDims,
        int embeddingTimeoutMs,
        int bm25TopK,
        int denseTopK,
        int rrfK,
        int defaultTopK,
        int maxTopK,
        double abstainThreshold,
        int injectionBudgetChars,
        double denseMinScore,
        String rerankProvider,
        String rerankBaseUrl,
        String rerankApiKey,
        String rerankModel,
        int rerankTimeoutMs,
        int rerankTopN,
        double rerankSkipGap,
        String extractProvider,
        String extractBaseUrl,
        String extractApiKey,
        String extractModel,
        int extractTimeoutMs,
        int extractMaxItems,
        double extractMinConfidence,
        // 分层策略参数。放环境变量而不是控制台：调它不改模型、不重建索引，但会改变
        // 「哪条记忆被注入」，属于部署级策略，改完重启一次更符合预期。
        int tierWorkingSetTtlHours,
        int tierArchiveAfterDays,
        int tierPromoteAccess,
        int tierPinnedBudgetChars,
        // 多模态：附件落本地卷，PG 只存元数据
        String assetsDir,
        long assetMaxBytes,
        String visionProvider,
        String visionBaseUrl,
        String visionApiKey,
        String visionModel,
        int visionTimeoutMs,
        // 程序性记忆：轨迹 → 策略 → 世界模型 → Skill。参数都是「学多快」的旋钮，
        // 和分层参数一样属于部署级策略，走环境变量、改完重启。
        boolean procEnabled,
        double procGamma,
        double procReflectionBonus,
        int procMinSupport,
        double procMinSuccess,
        int procCrystallizeSupport,
        double procCrystallizeSuccess,
        // 行级安全：业务请求（/v1/memory/*）在声明租户后切到受限角色，
        // 让「忘写 tenant_id」由数据库兜住。关掉只影响纵深防御，应用层过滤仍在。
        boolean rlsEnabled,
        String rlsRole
) {
    /** 空间标识：一个模型 + 一套维度 = 一个 vector space。 */
    public String spaceId() {
        return embeddingModel + "_" + embeddingDims;
    }

    public boolean embeddingIsHttp() {
        return "http".equalsIgnoreCase(embeddingProvider);
    }

    public boolean rerankIsHttp() {
        return "http".equalsIgnoreCase(rerankProvider);
    }

    public boolean extractIsHttp() {
        return "http".equalsIgnoreCase(extractProvider);
    }

    public boolean visionIsHttp() {
        return "http".equalsIgnoreCase(visionProvider);
    }
}
