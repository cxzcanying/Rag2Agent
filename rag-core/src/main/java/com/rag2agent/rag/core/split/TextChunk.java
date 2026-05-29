package com.rag2agent.rag.core.split;

import java.util.Map;

public record TextChunk(String id, String documentId, String content, int position, Map<String, Object> metadata) {}
