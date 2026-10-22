package com.rag2agent.bootstrap.tool.impl;

import com.rag2agent.bootstrap.service.AgentMemoryService;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import com.rag2agent.bootstrap.tool.Tool;
import com.rag2agent.bootstrap.tool.ToolDescriptor;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SaveAgentMemoryTool implements Tool {

    private final AgentMemoryService memoryService;
    private final KnowledgeBaseService knowledgeBaseService;

    public SaveAgentMemoryTool(
            AgentMemoryService memoryService, KnowledgeBaseService knowledgeBaseService) {
        this.memoryService = memoryService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Override
    public void validateAccess(Long userId, Map<String, Object> arguments) {
        knowledgeBaseService.requireWritable(userId, ((Number) arguments.get("kb_id")).longValue());
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "save_agent_memory",
                "保存经用户批准的知识库长期记忆",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "kb_id", Map.of("type", "integer", "description", "知识库 ID"),
                                "content", Map.of("type", "string", "description", "记忆内容，最多 4000 字"),
                                "memory_type", Map.of("type", "string", "description", "事实/偏好/决策"),
                                "source", Map.of("type", "string", "description", "来源说明")),
                        "required", List.of("kb_id", "content", "memory_type", "source")),
                true);
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        throw new UnsupportedOperationException("save_agent_memory 需要用户上下文");
    }

    @Override
    public String execute(Long userId, Map<String, Object> arguments) {
        long id = memoryService.remember(
                userId,
                ((Number) arguments.get("kb_id")).longValue(),
                "KB",
                (String) arguments.get("memory_type"),
                (String) arguments.get("content"),
                (String) arguments.get("source"),
                0.8,
                null);
        return "记忆已保存: " + id;
    }
}
