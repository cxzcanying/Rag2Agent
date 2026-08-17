package com.rag2agent.infra.ai.model;

import java.util.List;
import java.util.Map;

public record ChatCompletionRequest(
        String provider,
        String model,
        List<ChatMessage> messages,
        Map<String, Object> options,
        List<ToolDef> tools) {

    public ChatCompletionRequest(String provider, String model, List<ChatMessage> messages, Map<String, Object> options) {
        this(provider, model, messages, options, List.of());
    }
}
