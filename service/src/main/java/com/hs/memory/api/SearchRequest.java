package com.hs.memory.api;

import java.util.List;

public record SearchRequest(
        String query,
        Integer topK,
        String mode,
        String projectId,
        String userId,
        String sessionId,
        String asOf,
        List<String> kinds,
        List<String> scopes,
        /**
         * 要检索的层级。空 = T0/T1/T2；要归档层必须显式写 ["T3"] 或带上它。
         * 这是「默认不翻旧账」的唯一开关，不做成布尔参数是免得再叠一个组合维度。
         */
        List<String> tiers
) {
}
