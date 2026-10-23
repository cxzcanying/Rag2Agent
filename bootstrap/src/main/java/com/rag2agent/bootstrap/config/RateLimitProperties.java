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
    private int fallbackLimit = 5;
    private int fallbackWindowSeconds = 60;
    private int tenantLimit = 600;
    private int globalLimit = 6000;
    private int tenantFallbackLimit = 30;
    private int globalFallbackLimit = 300;
    private int loginFailureLimit = 5;
    private int loginFailureWindowSeconds = 900;
    private int loginLockSeconds = 900;

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

    public int getFallbackLimit() {
        return fallbackLimit;
    }

    public void setFallbackLimit(int fallbackLimit) {
        this.fallbackLimit = Math.max(1, fallbackLimit);
    }

    public int getFallbackWindowSeconds() {
        return fallbackWindowSeconds;
    }

    public void setFallbackWindowSeconds(int fallbackWindowSeconds) {
        this.fallbackWindowSeconds = Math.max(1, fallbackWindowSeconds);
    }

    public int getTenantLimit() { return tenantLimit; }
    public void setTenantLimit(int value) { tenantLimit = Math.max(1, value); }
    public int getGlobalLimit() { return globalLimit; }
    public void setGlobalLimit(int value) { globalLimit = Math.max(1, value); }
    public int getTenantFallbackLimit() { return tenantFallbackLimit; }
    public void setTenantFallbackLimit(int value) { tenantFallbackLimit = Math.max(1, value); }
    public int getGlobalFallbackLimit() { return globalFallbackLimit; }
    public void setGlobalFallbackLimit(int value) { globalFallbackLimit = Math.max(1, value); }

    public int getLoginFailureLimit() { return loginFailureLimit; }
    public void setLoginFailureLimit(int value) { loginFailureLimit = Math.max(1, value); }
    public int getLoginFailureWindowSeconds() { return loginFailureWindowSeconds; }
    public void setLoginFailureWindowSeconds(int value) { loginFailureWindowSeconds = Math.max(1, value); }
    public int getLoginLockSeconds() { return loginLockSeconds; }
    public void setLoginLockSeconds(int value) { loginLockSeconds = Math.max(1, value); }
}
