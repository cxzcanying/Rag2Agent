package com.rag2agent.rag.core.retrieval;

import java.util.Map;

public record RetrievalResult(String chunkId, String content, double score, Map<String, Object> metadata) {}
