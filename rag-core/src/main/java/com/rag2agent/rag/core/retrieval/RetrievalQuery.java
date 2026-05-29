package com.rag2agent.rag.core.retrieval;

import java.util.Map;

public record RetrievalQuery(String query, int topK, Map<String, Object> filters) {}
