package com.rag2agent.infra.ai.model;

import java.util.List;

public record EmbeddingRequest(String provider, String model, List<String> inputs) {}
