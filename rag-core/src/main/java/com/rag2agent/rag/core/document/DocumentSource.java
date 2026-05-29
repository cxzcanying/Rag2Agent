package com.rag2agent.rag.core.document;

import java.net.URI;
import java.util.Map;

public record DocumentSource(String documentId, String fileName, URI uri, String contentType, Map<String, Object> metadata) {}
