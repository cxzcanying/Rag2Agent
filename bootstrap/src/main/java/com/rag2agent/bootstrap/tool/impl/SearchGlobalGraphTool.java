package com.rag2agent.bootstrap.tool.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.Neo4jGraphProperties;
import com.rag2agent.bootstrap.graph.GraphCommunityService;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import com.rag2agent.bootstrap.tool.Tool;
import com.rag2agent.bootstrap.tool.ToolDescriptor;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SearchGlobalGraphTool implements Tool {

    private final KnowledgeBaseService knowledgeBaseService;
    private final GraphCommunityService communityService;
    private final Neo4jGraphProperties properties;
    private final ObjectMapper objectMapper;

    public SearchGlobalGraphTool(
            KnowledgeBaseService knowledgeBaseService,
            GraphCommunityService communityService,
            Neo4jGraphProperties properties,
            ObjectMapper objectMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.communityService = communityService;
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
                "search_global_graph",
                "读取知识图谱社区摘要，用于跨文档主题归纳和全局问题",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "kb_id", Map.of("type", "integer", "description", "知识库 ID"),
                                "limit", Map.of("type", "integer", "description", "摘要数量，默认 10")),
                        "required", List.of("kb_id")),
                false);
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        if (!properties.isEnabled()) {
            return "{\"enabled\":false,\"summaries\":[],\"fallback\":\"text_search\"}";
        }
        int limit = arguments.get("limit") == null
                ? 10
                : ((Number) arguments.get("limit")).intValue();
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "enabled", true,
                    "summaries", communityService.globalSummaries(
                            ((Number) arguments.get("kb_id")).longValue(), limit)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("图社区摘要序列化失败", exception);
        }
    }
}
