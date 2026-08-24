package com.rag2agent.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag2agent.search")
public class RetrievalProperties {

    private long parallelTimeoutMillis = 10_000;

    public long getParallelTimeoutMillis() {
        return parallelTimeoutMillis;
    }

    public void setParallelTimeoutMillis(long parallelTimeoutMillis) {
        this.parallelTimeoutMillis = Math.max(100, parallelTimeoutMillis);
    }
}
