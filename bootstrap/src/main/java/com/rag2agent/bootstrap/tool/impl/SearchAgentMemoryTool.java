package com.rag2agent.bootstrap.tool.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.service.AgentMemoryService;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import com.rag2agent.bootstrap.tool.Tool;
import com.rag2agent.bootstrap.tool.ToolDescriptor;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SearchAgentMemoryTool implements Tool {

    private final AgentMemoryService memoryService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;

    public SearchAgentMemoryTool(
            AgentMemoryService memoryService,
            KnowledgeBaseService knowledgeBaseService,
            ObjectMapper objectMapper) {
        this.memoryService = memoryService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void validateAccess(Long userId, Map<String, Object> arguments) {
        if (arguments.get("kb_id") != null) {
            knowledgeBaseService.requireReadable(userId, ((Number) arguments.get("kb_id")).longValue());
        }
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "search_agent_memory",
                "检索当前用户已保存的长期记忆，结果按相关度排序",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "检索问题"),
                                "kb_id", Map.of("type", "integer", "description", "可选知识库 ID"),
                                "top_k", Map.of("type", "integer", "description", "返回条数，默认 5")),
                        "required", List.of("query")),
                false);
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        throw new UnsupportedOperationException("search_agent_memory 需要用户上下文");
    }

    @Override
    public String execute(Long userId, Map<String, Object> arguments) {
        String query = (String) arguments.get("query");
        Long kbId = arguments.get("kb_id") == null
                ? null
                : ((Number) arguments.get("kb_id")).longValue();
        int topK = arguments.get("top_k") == null
                ? 5
                : Math.min(20, ((Number) arguments.get("top_k")).intValue());
        try {
            return objectMapper.writeValueAsString(memoryService.search(userId, kbId, query, topK));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("记忆结果序列化失败", exception);
        }
    }
}
