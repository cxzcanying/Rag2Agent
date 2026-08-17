package com.rag2agent.bootstrap.agent;

/**
 * 引用溯源：回答引用的文档片段。
 */
public record Reference(Long documentId, Integer chunkIndex, String content) {}
