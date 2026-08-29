package com.rag2agent.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag2agent.search")
public class RetrievalProperties {

    private long parallelTimeoutMillis = 10_000;
    private String chineseSearchMode = "auto";

    public long getParallelTimeoutMillis() {
        return parallelTimeoutMillis;
    }

    public void setParallelTimeoutMillis(long parallelTimeoutMillis) {
        this.parallelTimeoutMillis = Math.max(100, parallelTimeoutMillis);
    }

    public String getChineseSearchMode() {
        return chineseSearchMode;
    }

    public void setChineseSearchMode(String chineseSearchMode) {
        String value = chineseSearchMode == null ? "auto" : chineseSearchMode.trim().toLowerCase(java.util.Locale.ROOT);
        this.chineseSearchMode = switch (value) {
            case "auto", "zhparser", "bigram" -> value;
            default -> "auto";
        };
    }
}
