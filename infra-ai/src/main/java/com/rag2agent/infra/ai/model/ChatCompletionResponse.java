package com.rag2agent.infra.ai.model;

import java.util.Map;

public record ChatCompletionResponse(String content, String finishReason, Map<String, Object> usage) {}
