package com.rag2agent.infra.ai.model;

import java.util.List;

public record VectorSearchResponse(List<VectorSearchResult> results) {}
