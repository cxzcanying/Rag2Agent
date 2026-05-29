package com.rag2agent.infra.ai.model;

import java.util.List;
import java.util.Map;

public record VectorSearchRequest(List<Float> vector, int topK, Map<String, Object> filters) {}
