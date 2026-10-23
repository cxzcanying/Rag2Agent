package com.rag2agent.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag2agent.ingest")
public class IngestProperties {

    private boolean rocketmqEnabled;
    private boolean localFallbackEnabled = true;
    private int maxConcurrentDocuments = 2;

    public boolean isRocketmqEnabled() {
        return rocketmqEnabled;
    }

    public void setRocketmqEnabled(boolean rocketmqEnabled) {
        this.rocketmqEnabled = rocketmqEnabled;
    }

    public boolean isLocalFallbackEnabled() {
        return localFallbackEnabled;
    }

    public void setLocalFallbackEnabled(boolean localFallbackEnabled) {
        this.localFallbackEnabled = localFallbackEnabled;
    }

    public int getMaxConcurrentDocuments() {
        return maxConcurrentDocuments;
    }

    public void setMaxConcurrentDocuments(int maxConcurrentDocuments) {
        this.maxConcurrentDocuments = Math.max(1, maxConcurrentDocuments);
    }
}
