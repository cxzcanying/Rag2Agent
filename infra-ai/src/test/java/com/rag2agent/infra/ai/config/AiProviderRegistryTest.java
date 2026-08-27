package com.rag2agent.infra.ai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rag2agent.infra.ai.config.AiProviderProperties.Provider;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiProviderRegistryTest {

    @Test
    void selectsConfiguredOpenAiCompatibleProvider() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setActiveChatProvider("custom-openai");
        properties.setProviders(List.of(
                provider("deepseek", "chat"),
                provider("custom-openai", "chat")));

        Provider selected = new AiProviderRegistry(properties).active("chat");

        assertEquals("custom-openai", selected.getName());
    }

    @Test
    void rejectsProviderWithoutRequestedCapability() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setActiveEmbeddingProvider("chat-only");
        properties.setProviders(List.of(provider("chat-only", "chat")));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new AiProviderRegistry(properties).active("embedding"));

        assertEquals(
                "AI provider 配置无效: chat-only（capability: embedding），应且只能匹配一个启用项",
                exception.getMessage());
    }

    private static Provider provider(String name, String capability) {
        Provider provider = new Provider();
        provider.setName(name);
        provider.setEnabled(true);
        provider.setCapabilities(List.of(capability));
        provider.setBaseUrl("https://example.com/v1");
        provider.setChatModel("chat-model");
        provider.setEmbeddingModel("embedding-model");
        provider.setRerankModel("rerank-model");
        return provider;
    }
}
