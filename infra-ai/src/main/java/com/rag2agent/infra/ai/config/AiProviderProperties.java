package com.rag2agent.infra.ai.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag2agent.ai")
public class AiProviderProperties {

    private List<Provider> providers = new ArrayList<>();
    private String activeChatProvider = "deepseek";
    private String activeEmbeddingProvider = "siliconflow";
    private String activeRerankProvider = "siliconflow";

    public List<Provider> getProviders() {
        return providers;
    }

    public void setProviders(List<Provider> providers) {
        this.providers = providers;
    }

    public String getActiveChatProvider() {
        return activeChatProvider;
    }

    public void setActiveChatProvider(String activeChatProvider) {
        this.activeChatProvider = activeChatProvider;
    }

    public String getActiveEmbeddingProvider() {
        return activeEmbeddingProvider;
    }

    public void setActiveEmbeddingProvider(String activeEmbeddingProvider) {
        this.activeEmbeddingProvider = activeEmbeddingProvider;
    }

    public String getActiveRerankProvider() {
        return activeRerankProvider;
    }

    public void setActiveRerankProvider(String activeRerankProvider) {
        this.activeRerankProvider = activeRerankProvider;
    }

    public static class Provider {

        private String name;
        private String baseUrl;
        private String apiKey;
        private List<String> capabilities = new ArrayList<>();
        private boolean enabled;
        private String chatModel;
        private String chatModelPro;
        private String embeddingModel;
        private String rerankModel;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public List<String> getCapabilities() {
            return capabilities;
        }

        public void setCapabilities(List<String> capabilities) {
            this.capabilities = capabilities;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getChatModel() {
            return chatModel;
        }

        public void setChatModel(String chatModel) {
            this.chatModel = chatModel;
        }

        public String getChatModelPro() {
            return chatModelPro;
        }

        public void setChatModelPro(String chatModelPro) {
            this.chatModelPro = chatModelPro;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public String getRerankModel() {
            return rerankModel;
        }

        public void setRerankModel(String rerankModel) {
            this.rerankModel = rerankModel;
        }
    }
}
