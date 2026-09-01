package com.rag2agent.bootstrap.config;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
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
        private double peakMultiplier = 1;
        private String peakWindowsUtc = "";
        private boolean peakWeekdaysOnly;
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
        public double getPeakMultiplier() { return peakMultiplier; }
        public void setPeakMultiplier(double value) { peakMultiplier = Math.max(1, value); }
        public String getPeakWindowsUtc() { return peakWindowsUtc; }
        public void setPeakWindowsUtc(String value) { peakWindowsUtc = value == null ? "" : value.trim(); }
        public boolean isPeakWeekdaysOnly() { return peakWeekdaysOnly; }
        public void setPeakWeekdaysOnly(boolean value) { peakWeekdaysOnly = value; }

        public ResolvedPrice resolve(Instant instant) {
            boolean peak = isPeak(instant);
            double multiplier = peak ? peakMultiplier : 1;
            String band = peakWindowsUtc.isBlank() ? "fixed" : peak ? "peak" : "off-peak";
            return new ResolvedPrice(
                    promptPerMillion * multiplier,
                    completionPerMillion * multiplier,
                    cacheReadPerMillion * multiplier,
                    currency,
                    version + "-" + band);
        }

        private boolean isPeak(Instant instant) {
            if (peakWindowsUtc.isBlank() || peakMultiplier <= 1) return false;
            var utc = instant.atZone(ZoneOffset.UTC);
            DayOfWeek day = utc.getDayOfWeek();
            if (peakWeekdaysOnly && (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY)) return false;
            LocalTime time = utc.toLocalTime();
            for (String window : peakWindowsUtc.split(",")) {
                String[] bounds = window.trim().split("-", 2);
                if (bounds.length == 2) {
                    LocalTime start = LocalTime.parse(bounds[0].trim());
                    LocalTime end = LocalTime.parse(bounds[1].trim());
                    if (!time.isBefore(start) && time.isBefore(end)) return true;
                }
            }
            return false;
        }

        public record ResolvedPrice(
                double promptPerMillion,
                double completionPerMillion,
                double cacheReadPerMillion,
                String currency,
                String version) {}
    }
}
