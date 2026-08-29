package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.config.TokenCostProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.List;
import com.rag2agent.infra.ai.model.ChatMessage;
import org.springframework.stereotype.Service;

/** Token 统计与成本估算；没有 provider 价格时只记录 token，不伪造金额。 */
@Service
public class TokenCostService {
    private final TokenCostProperties properties;
    private final MeterRegistry meterRegistry;

    public TokenCostService(TokenCostProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public void record(Map<String, Object> usage, String provider, String model) {
        if (usage == null) return;
        long prompt = number(usage.get("prompt_tokens"));
        long completion = number(usage.get("completion_tokens"));
        TokenCostProperties.Price price = properties.getPrices().get(provider + "/" + model);
        if (price != null) {
            double cost = prompt * price.getPromptPerMillion() / 1_000_000d
                    + completion * price.getCompletionPerMillion() / 1_000_000d;
            meterRegistry.counter("rag2agent.ai.cost", "provider", provider, "model", model).increment(cost);
        }
    }

    public void recordEstimated(List<ChatMessage> messages, String provider, String model) {
        int chars = messages == null ? 0 : messages.stream()
                .map(ChatMessage::content).filter(java.util.Objects::nonNull)
                .mapToInt(String::length).sum();
        meterRegistry.counter("rag2agent.ai.tokens", "type", "estimated",
                "provider", provider == null || provider.isBlank() ? "unknown" : provider,
                "model", model == null || model.isBlank() ? "configured" : model)
                .increment(Math.max(0, (chars + 3) / 4));
    }

    private long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
}
