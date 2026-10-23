package com.rag2agent.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag2agent.memory.extraction")
public class MemoryExtractionProperties {

    private boolean enabled;
    private int maxFacts = 5;
    private double minConfidence = 0.7;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxFacts() {
        return maxFacts;
    }

    public void setMaxFacts(int maxFacts) {
        this.maxFacts = Math.min(Math.max(1, maxFacts), 20);
    }

    public double getMinConfidence() {
        return minConfidence;
    }

    public void setMinConfidence(double minConfidence) {
        this.minConfidence = Math.min(Math.max(0, minConfidence), 1);
    }
}
