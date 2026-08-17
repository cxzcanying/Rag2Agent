package com.rag2agent.bootstrap.tool.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.service.HybridSearchService;
import com.rag2agent.bootstrap.tool.Tool;
import com.rag2agent.bootstrap.tool.ToolDescriptor;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 内部只读工具：检索知识库，返回相关片段及引用（document_id/chunk_index）。
 */
@Component
public class SearchKnowledgeBaseTool implements Tool {

    private final HybridSearchService searchService;
    private final ObjectMapper objectMapper;

    public SearchKnowledgeBaseTool(HybridSearchService searchService, ObjectMapper objectMapper) {
        this.searchService = searchService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "search_knowledge_base",
                "检索指定知识库，返回相关文档片段及引用（document_id、chunk_index）",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "kb_id", Map.of("type", "integer", "description", "知识库 ID"),
                                "query", Map.of("type", "string", "description", "检索问题"),
                                "top_k", Map.of("type", "integer", "description", "返回条数，默认 5")),
                        "required", List.of("kb_id", "query")),
                false);
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Long kbId = ((Number) arguments.get("kb_id")).longValue();
        String query = (String) arguments.get("query");
        int topK = arguments.get("top_k") == null ? 5 : ((Number) arguments.get("top_k")).intValue();
        List<RetrievalResult> results = searchService.search(kbId, query, topK);

        // 只把模型需要的字段序列化出去，避免把整段文本全文塞进上下文
        List<Map<String, Object>> simplified = results.stream()
                .map(r -> Map.of(
                        "content", r.content(),
                        "document_id", r.metadata().getOrDefault("documentId", 0),
                        "chunk_index", r.metadata().getOrDefault("chunkIndex", 0),
                        "score", r.score()))
                .toList();
        try {
            return objectMapper.writeValueAsString(simplified);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("检索结果序列化失败", e);
        }
    }
}
