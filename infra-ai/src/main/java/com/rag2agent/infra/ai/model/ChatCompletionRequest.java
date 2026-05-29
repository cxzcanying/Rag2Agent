package com.rag2agent.infra.ai.model;

import java.util.List;
import java.util.Map;

public record ChatCompletionRequest(String provider, String model, List<ChatMessage> messages, Map<String, Object> options) {}
