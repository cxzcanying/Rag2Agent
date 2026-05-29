package com.rag2agent.infra.ai.model;

import java.util.Map;

public record VectorSearchResult(String id, double score, String content, Map<String, Object> metadata) {}
