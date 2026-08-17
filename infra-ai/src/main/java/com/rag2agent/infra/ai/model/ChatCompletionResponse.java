package com.rag2agent.infra.ai.model;

import java.util.List;
import java.util.Map;

public record ChatCompletionResponse(
        String content,
        String finishReason,
        Map<String, Object> usage,
        List<ToolCall> toolCalls) {

    public ChatCompletionResponse(String content, String finishReason, Map<String, Object> usage) {
        this(content, finishReason, usage, List.of());
    }
}
