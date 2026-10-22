package com.rag2agent.bootstrap.tool.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.Neo4jGraphProperties;
import com.rag2agent.bootstrap.graph.KnowledgeGraphRepository;
import com.rag2agent.bootstrap.graph.KnowledgeGraphRepository.GraphPath;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import com.rag2agent.bootstrap.tool.Tool;
import com.rag2agent.bootstrap.tool.ToolDescriptor;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SearchKnowledgeGraphTool implements Tool {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeGraphRepository repository;
    private final Neo4jGraphProperties properties;
    private final ObjectMapper objectMapper;

    public SearchKnowledgeGraphTool(
            KnowledgeBaseService knowledgeBaseService,
            KnowledgeGraphRepository repository,
            Neo4jGraphProperties properties,
            ObjectMapper objectMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void validateAccess(Long userId, Map<String, Object> arguments) {
        knowledgeBaseService.requireReadable(userId, ((Number) arguments.get("kb_id")).longValue());
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "search_knowledge_graph",
                "查询知识图谱中的实体关系和 1~2 跳路径，返回关系证据参考",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "kb_id", Map.of("type", "integer", "description", "知识库 ID"),
                                "query", Map.of("type", "string", "description", "实体名或关系问题关键词"),
                                "limit", Map.of("type", "integer", "description", "返回路径数，默认 10")),
                        "required", List.of("kb_id", "query")),
                false);
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        if (!properties.isEnabled()) {
            return "{\"enabled\":false,\"paths\":[],\"fallback\":\"text_search\"}";
        }
        Long kbId = ((Number) arguments.get("kb_id")).longValue();
        String query = (String) arguments.get("query");
        int limit = arguments.get("limit") == null
                ? 10
                : Math.min(20, ((Number) arguments.get("limit")).intValue());
        List<GraphPath> paths = repository.searchPaths(kbId, query, limit);
        try {
            return objectMapper.writeValueAsString(Map.of("enabled", true, "paths", paths));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("图路径序列化失败", exception);
        }
    }
}
