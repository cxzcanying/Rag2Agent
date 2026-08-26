package com.rag2agent.bootstrap.config;

import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author 21311
 */
@ConfigurationProperties(prefix = "rag2agent.rate-limit")
public class RateLimitProperties {

    @Setter
    private boolean enabled = true;
    private int limit = 60;
    private int windowSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = Math.max(1, limit);
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = Math.max(1, windowSeconds);
    }
}
