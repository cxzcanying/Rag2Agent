package com.rag2agent.infra.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI 外部调用的统一可靠性策略；只覆盖无副作用的模型请求。 */
@ConfigurationProperties(prefix = "rag2agent.ai.resilience")
public class AiResilienceProperties {

    private boolean enabled = true;
    private int maxAttempts = 3;
    private long initialBackoffMillis = 200;
    private long maxBackoffMillis = 2_000;
    private double jitterRatio = 0.2;
    private int failureThreshold = 5;
    private long openStateMillis = 30_000;
    private int maxConcurrentRequests = 16;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = Math.max(1, Math.min(maxAttempts, 5)); }
    public long getInitialBackoffMillis() { return initialBackoffMillis; }
    public void setInitialBackoffMillis(long value) { this.initialBackoffMillis = Math.max(0, value); }
    public long getMaxBackoffMillis() { return maxBackoffMillis; }
    public void setMaxBackoffMillis(long value) { this.maxBackoffMillis = Math.max(initialBackoffMillis, value); }
    public double getJitterRatio() { return jitterRatio; }
    public void setJitterRatio(double value) { this.jitterRatio = Math.max(0, Math.min(value, 1)); }
    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int value) { this.failureThreshold = Math.max(1, value); }
    public long getOpenStateMillis() { return openStateMillis; }
    public void setOpenStateMillis(long value) { this.openStateMillis = Math.max(100, value); }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public void setMaxConcurrentRequests(int value) { this.maxConcurrentRequests = Math.max(1, value); }
}
