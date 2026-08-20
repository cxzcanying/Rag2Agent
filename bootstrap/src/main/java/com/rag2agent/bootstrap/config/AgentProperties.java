package com.rag2agent.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent 上下文预算。先使用保守估算，后续可替换成真实 tokenizer。 */
@ConfigurationProperties(prefix = "rag2agent.agent")
public class AgentProperties {

    private int contextTokenBudget = 6000;
    private int maxInputChars = 12000;
    private int summaryMaxChars = 1200;
    private int maxOutputTokens = 1024;

    public int getContextTokenBudget() {
        return contextTokenBudget;
    }

    public void setContextTokenBudget(int contextTokenBudget) {
        this.contextTokenBudget = contextTokenBudget;
    }

    public int getMaxInputChars() {
        return maxInputChars;
    }

    public void setMaxInputChars(int maxInputChars) {
        this.maxInputChars = maxInputChars;
    }

    public int getSummaryMaxChars() {
        return summaryMaxChars;
    }

    public void setSummaryMaxChars(int summaryMaxChars) {
        this.summaryMaxChars = summaryMaxChars;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }
}
