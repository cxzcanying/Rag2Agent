package com.rag2agent.rag.core.document;

import java.util.List;
import java.util.Map;

public record ParsedDocument(String documentId, String text, List<String> pages, Map<String, Object> metadata) {}
