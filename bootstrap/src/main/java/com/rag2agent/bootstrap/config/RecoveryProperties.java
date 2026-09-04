package com.rag2agent.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 后台恢复任务配置：审批超时与异步入库中断任务的扫描参数。
 * @author 21311
 */
@ConfigurationProperties(prefix = "rag2agent.recovery")
public class RecoveryProperties {

    /** 是否开启后台扫描，生产或测试环境可一键关闭。 */
    private boolean enabled = true;

    /** 入库任务卡在中间态多久后视为中断（秒），需大于入库锁租约 TTL 的合理窗口才安全。 */
    private long ingestStaleSeconds = 30 * 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getIngestStaleSeconds() {
        return ingestStaleSeconds;
    }

    public void setIngestStaleSeconds(long ingestStaleSeconds) {
        this.ingestStaleSeconds = Math.max(60, ingestStaleSeconds);
    }
}
