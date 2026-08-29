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
        private double promptPerMillion;
        private double completionPerMillion;
        public double getPromptPerMillion() { return promptPerMillion; }
        public void setPromptPerMillion(double value) { promptPerMillion = Math.max(0, value); }
        public double getCompletionPerMillion() { return completionPerMillion; }
        public void setCompletionPerMillion(double value) { completionPerMillion = Math.max(0, value); }
    }
}
