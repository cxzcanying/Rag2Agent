package com.rag2agent.bootstrap.agent;

import java.util.Map;

/**
 * 引用溯源：回答引用的文档片段。
 */
public record Reference(
        Long documentId,
        Integer chunkIndex,
        String content,
        Map<String, Object> metadata) {

    public Reference {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public Reference(Long documentId, Integer chunkIndex, String content) {
        this(documentId, chunkIndex, content, Map.of());
    }
}
