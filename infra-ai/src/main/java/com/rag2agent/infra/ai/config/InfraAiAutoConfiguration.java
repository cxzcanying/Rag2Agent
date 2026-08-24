package com.rag2agent.infra.ai.config;

import com.rag2agent.infra.ai.client.ChatModelClient;
import com.rag2agent.infra.ai.client.EmbeddingClient;
import com.rag2agent.infra.ai.client.RerankClient;
import com.rag2agent.infra.ai.client.impl.OpenAiChatModelClient;
import com.rag2agent.infra.ai.client.impl.OpenAiEmbeddingClient;
import com.rag2agent.infra.ai.client.impl.OpenAiRerankClient;
import com.rag2agent.infra.ai.config.AiProviderProperties.Provider;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
@EnableConfigurationProperties({AiProviderProperties.class, AiResilienceProperties.class})
public class InfraAiAutoConfiguration {

    @Bean
    public ChatModelClient chatModelClient(
            AiProviderProperties properties, AiResilienceProperties resilience, MeterRegistry meterRegistry) {
        return new OpenAiChatModelClient(findProvider(properties, "deepseek", "chat"), resilience, meterRegistry);
    }

    @Bean
    public EmbeddingClient embeddingClient(
            AiProviderProperties properties, AiResilienceProperties resilience, MeterRegistry meterRegistry) {
        return new OpenAiEmbeddingClient(
                findProvider(properties, "siliconflow", "embedding"), resilience, meterRegistry);
    }

    @Bean
    public RerankClient rerankClient(
            AiProviderProperties properties, AiResilienceProperties resilience, MeterRegistry meterRegistry) {
        return new OpenAiRerankClient(findProvider(properties, "siliconflow", "rerank"), resilience, meterRegistry);
    }

    private Provider findProvider(AiProviderProperties properties, String name, String capability) {
        List<Provider> providers = properties.getProviders().stream()
                .filter(Provider::isEnabled)
                .filter(provider -> name.equals(provider.getName()))
                .filter(provider -> provider.getCapabilities().contains(capability))
                .toList();
        if (providers.isEmpty()) {
            throw new IllegalStateException(
                    "缺少启用的 AI provider: " + name + "（capability: " + capability + "），请检查配置");
        }
        return providers.get(0);
    }
}
