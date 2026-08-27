package com.rag2agent.infra.ai.config;

import com.rag2agent.infra.ai.client.ChatModelClient;
import com.rag2agent.infra.ai.client.EmbeddingClient;
import com.rag2agent.infra.ai.client.RerankClient;
import com.rag2agent.infra.ai.client.impl.OpenAiChatModelClient;
import com.rag2agent.infra.ai.client.impl.OpenAiEmbeddingClient;
import com.rag2agent.infra.ai.client.impl.OpenAiRerankClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
@EnableConfigurationProperties({AiProviderProperties.class, AiResilienceProperties.class})
public class InfraAiAutoConfiguration {

    @Bean
    public AiProviderRegistry aiProviderRegistry(AiProviderProperties properties) {
        return new AiProviderRegistry(properties);
    }

    @Bean
    public ChatModelClient chatModelClient(
            AiProviderRegistry providers, AiResilienceProperties resilience, MeterRegistry meterRegistry) {
        return new OpenAiChatModelClient(providers.active("chat"), resilience, meterRegistry);
    }

    @Bean
    public EmbeddingClient embeddingClient(
            AiProviderRegistry providers, AiResilienceProperties resilience, MeterRegistry meterRegistry) {
        return new OpenAiEmbeddingClient(
                providers.active("embedding"), resilience, meterRegistry);
    }

    @Bean
    public RerankClient rerankClient(
            AiProviderRegistry providers, AiResilienceProperties resilience, MeterRegistry meterRegistry) {
        return new OpenAiRerankClient(providers.active("rerank"), resilience, meterRegistry);
    }
}
