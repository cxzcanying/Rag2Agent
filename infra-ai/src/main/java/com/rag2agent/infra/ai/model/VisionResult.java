package com.rag2agent.infra.ai.model;

import java.util.Map;

public record VisionResult(
        String content,
        String provider,
        String model,
        Map<String, Object> usage) {

    public VisionResult(String content, String provider, String model) {
        this(content, provider, model, Map.of());
    }
}
