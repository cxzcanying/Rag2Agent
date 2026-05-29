package com.rag2agent.infra.ai.model;

import java.util.List;

public record EmbeddingResponse(List<List<Float>> vectors) {}
