package com.rag2agent.bootstrap.config;

import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag2agent.cache.embedding")
public class EmbeddingCacheProperties {

    @Setter
    private boolean enabled = true;
    private int maxEntries = 10_000;
    private int ttlSeconds = 3_600;

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(int ttlSeconds) {
        this.ttlSeconds = Math.max(1, ttlSeconds);
    }
}
