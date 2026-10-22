package com.rag2agent.bootstrap.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.service.AgentMemoryService;
import com.rag2agent.bootstrap.service.AgentMemoryService.MemoryRecord;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentMemoryToolsTest {

    @Test
    void searchesWithUserContextAndSavesOnlyAfterApprovalBoundary() {
        AgentMemoryService memory = mock(AgentMemoryService.class);
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        when(memory.search(2L, 3L, "项目偏好", 5)).thenReturn(List.of(
                new MemoryRecord(1, 3L, "KB", "preference", "偏好简洁回答", "user", 0.8, 0.9)));
        when(memory.remember(2L, 3L, "KB", "fact", "项目使用 Java", "chat", 0.8, null))
                .thenReturn(9L);

        SearchAgentMemoryTool search = new SearchAgentMemoryTool(
                memory, kb, new ObjectMapper());
        SaveAgentMemoryTool save = new SaveAgentMemoryTool(memory, kb);

        String result = search.execute(2L, Map.of("query", "项目偏好", "kb_id", 3));
        String saved = save.execute(2L, Map.of(
                "kb_id", 3, "content", "项目使用 Java",
                "memory_type", "fact", "source", "chat"));

        assertTrue(result.contains("偏好简洁回答"));
        assertEquals("记忆已保存: 9", saved);
        assertTrue(save.descriptor().requiresApproval());
    }
}
