package com.rag2agent.infra.ai.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag2agent.ai.docling")
public class DoclingProperties {

    private boolean enabled;
    private String baseUrl = "http://localhost:5001";
    private String endpoint = "/v1/convert/file";
    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 180;
    private int writeTimeoutSeconds = 60;
    private String tableMode = "accurate";
    private String ocrPreset = "rapidocr";
    private List<String> ocrLanguages = new ArrayList<>(List.of("ch", "en"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public int getWriteTimeoutSeconds() {
        return writeTimeoutSeconds;
    }

    public void setWriteTimeoutSeconds(int writeTimeoutSeconds) {
        this.writeTimeoutSeconds = writeTimeoutSeconds;
    }

    public String getTableMode() {
        return tableMode;
    }

    public void setTableMode(String tableMode) {
        this.tableMode = tableMode;
    }

    public String getOcrPreset() {
        return ocrPreset;
    }

    public void setOcrPreset(String ocrPreset) {
        this.ocrPreset = ocrPreset;
    }

    public List<String> getOcrLanguages() {
        return ocrLanguages;
    }

    public void setOcrLanguages(List<String> ocrLanguages) {
        this.ocrLanguages = ocrLanguages;
    }

}
