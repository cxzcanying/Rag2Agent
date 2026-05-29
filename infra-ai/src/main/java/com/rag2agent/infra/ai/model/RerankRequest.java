package com.rag2agent.infra.ai.model;

import java.util.List;

public record RerankRequest(String provider, String model, String query, List<String> documents, int topK) {}
