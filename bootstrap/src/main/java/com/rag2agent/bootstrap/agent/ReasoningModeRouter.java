package com.rag2agent.bootstrap.agent;

import java.util.Locale;
import java.util.Set;

/** 将配置值和问题复杂度收敛为供应商可传入的推理档位。 */
public final class ReasoningModeRouter {

    private static final Set<String> LEVELS =
            Set.of("off", "minimal", "low", "medium", "high", "max");
    private static final Set<String> COMPLEX_MARKERS =
            Set.of("分析", "比较", "推理", "多跳", "规划", "证明", "为什么", "跨文档");

    private ReasoningModeRouter() {}

    public static String resolve(String configured, String query) {
        String mode = configured == null || configured.isBlank()
                ? "off"
                : configured.trim().toLowerCase(Locale.ROOT);
        if (!"auto".equals(mode)) {
            if (!LEVELS.contains(mode)) {
                throw new IllegalArgumentException("reasoningEffort 配置无效: " + configured);
            }
            return mode;
        }
        if (query == null || query.isBlank()) {
            return "low";
        }
        if (COMPLEX_MARKERS.stream().anyMatch(query::contains) || query.length() > 300) {
            return "high";
        }
        return query.length() < 80 ? "low" : "medium";
    }
}
