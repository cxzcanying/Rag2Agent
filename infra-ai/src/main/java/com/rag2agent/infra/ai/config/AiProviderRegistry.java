package com.rag2agent.infra.ai.config;

import com.rag2agent.infra.ai.config.AiProviderProperties.Provider;
import java.util.List;
import java.util.Objects;

/** 按能力解析当前启用的 OpenAI-compatible provider。 */
public class AiProviderRegistry {

    private final AiProviderProperties properties;

    public AiProviderRegistry(AiProviderProperties properties) {
        this.properties = properties;
    }

    public Provider active(String capability) {
        String providerName = switch (capability) {
            case "chat" -> properties.getActiveChatProvider();
            case "embedding" -> properties.getActiveEmbeddingProvider();
            case "rerank" -> properties.getActiveRerankProvider();
            default -> throw new IllegalArgumentException("不支持的 AI capability: " + capability);
        };
        List<Provider> configured = properties.getProviders() == null ? List.of() : properties.getProviders();
        List<Provider> matches = configured.stream()
                .filter(Provider::isEnabled)
                .filter(provider -> Objects.equals(providerName, provider.getName()))
                .filter(provider -> provider.getCapabilities() != null
                        && provider.getCapabilities().contains(capability))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("AI provider 配置无效: " + providerName
                    + "（capability: " + capability + "），应且只能匹配一个启用项");
        }
        Provider selected = matches.getFirst();
        String model = switch (capability) {
            case "chat" -> selected.getChatModel();
            case "embedding" -> selected.getEmbeddingModel();
            case "rerank" -> selected.getRerankModel();
            default -> null;
        };
        if (selected.getBaseUrl() == null || selected.getBaseUrl().isBlank()
                || model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "AI provider 缺少 base-url 或默认模型: " + providerName + "（capability: " + capability + "）");
        }
        return selected;
    }
}
