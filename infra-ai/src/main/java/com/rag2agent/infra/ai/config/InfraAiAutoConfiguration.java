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

/**
 * AI 基础设施自动配置：注册 Provider 能力路由，并按 chat、embedding、rerank 能力创建对应的客户端 Bean。
 * 创建客户端时统一注入 AI 韧性配置和 Micrometer 指标注册器，使业务层只需依赖客户端接口。
 *
 * @author 21311
 */
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
