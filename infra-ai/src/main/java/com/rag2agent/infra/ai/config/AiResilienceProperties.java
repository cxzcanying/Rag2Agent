package com.rag2agent.infra.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI 外部调用的统一可靠性策略；只覆盖无副作用的模型请求。
 * @author 21311*/
@ConfigurationProperties(prefix = "rag2agent.ai.resilience")
public class AiResilienceProperties {

    private boolean enabled = true;  //是否启用韧性策略
    private int maxAttempts = 3;  //最大重试次数，默认 3 次
    private long initialBackoffMillis = 200;  //初始退避时间，默认 200ms
    private long maxBackoffMillis = 2_000;  //最大退避时间，默认 2 秒
    private double jitterRatio = 0.2;  //抖动比例，默认 20%
    private int failureThreshold = 5;  //熔断失败阈值，默认 5 次
    private long openStateMillis = 30_000;  //熔断打开时间，默认 30 秒
    private int maxConcurrentRequests = 16;  //单个 AI 客户端最大并发数，默认 16

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = Math.clamp(maxAttempts, 1, 5); }
    public long getInitialBackoffMillis() { return initialBackoffMillis; }
    public void setInitialBackoffMillis(long value) { this.initialBackoffMillis = Math.max(0, value); }
    public long getMaxBackoffMillis() { return maxBackoffMillis; }
    public void setMaxBackoffMillis(long value) { this.maxBackoffMillis = Math.max(initialBackoffMillis, value); }
    public double getJitterRatio() { return jitterRatio; }
    public void setJitterRatio(double value) { this.jitterRatio = Math.clamp(value, 0, 1); }
    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int value) { this.failureThreshold = Math.max(1, value); }
    public long getOpenStateMillis() { return openStateMillis; }
    public void setOpenStateMillis(long value) { this.openStateMillis = Math.max(100, value); }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public void setMaxConcurrentRequests(int value) { this.maxConcurrentRequests = Math.max(1, value); }
}
