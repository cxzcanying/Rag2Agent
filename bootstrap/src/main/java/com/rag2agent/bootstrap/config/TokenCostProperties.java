package com.rag2agent.bootstrap.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag2agent.ai.cost")
public class TokenCostProperties {
    private Map<String, Price> prices = new LinkedHashMap<>();

    public Map<String, Price> getPrices() { return prices; }
    public void setPrices(Map<String, Price> prices) { this.prices = prices == null ? new LinkedHashMap<>() : prices; }

    public static class Price {
        private String version = "yaml-1";
        private String currency = "CNY";
        private double promptPerMillion;
        private double completionPerMillion;
        private double cacheReadPerMillion;
        private double cacheWritePerMillion;
        public String getVersion() { return version; }
        public void setVersion(String value) { version = value == null || value.isBlank() ? "yaml-1" : value; }
        public String getCurrency() { return currency; }
        public void setCurrency(String value) { currency = value == null || value.isBlank() ? "CNY" : value; }
        public double getPromptPerMillion() { return promptPerMillion; }
        public void setPromptPerMillion(double value) { promptPerMillion = Math.max(0, value); }
        public double getCompletionPerMillion() { return completionPerMillion; }
        public void setCompletionPerMillion(double value) { completionPerMillion = Math.max(0, value); }
        public double getCacheReadPerMillion() { return cacheReadPerMillion; }
        public void setCacheReadPerMillion(double value) { cacheReadPerMillion = Math.max(0, value); }
        public double getCacheWritePerMillion() { return cacheWritePerMillion; }
        public void setCacheWritePerMillion(double value) { cacheWritePerMillion = Math.max(0, value); }
    }
}
