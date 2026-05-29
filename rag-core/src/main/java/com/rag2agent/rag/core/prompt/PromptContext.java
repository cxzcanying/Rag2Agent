package com.rag2agent.rag.core.prompt;

import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.util.List;
import java.util.Map;

public record PromptContext(String query, List<RetrievalResult> evidence, Map<String, Object> variables) {}
