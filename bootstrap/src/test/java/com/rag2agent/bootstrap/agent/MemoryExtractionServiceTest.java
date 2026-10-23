package com.rag2agent.bootstrap.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.MemoryExtractionProperties;
import com.rag2agent.bootstrap.service.AgentMemoryService;
import com.rag2agent.bootstrap.service.TokenCostService;
import com.rag2agent.infra.ai.client.ChatModelClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MemoryExtractionServiceTest {

    @Test
    void parsesFactsAndNormalizesUnknownMemoryType() {
        MemoryExtractionService service = new MemoryExtractionService(
                mock(JdbcTemplate.class),
                mock(ChatModelClient.class),
                mock(AgentMemoryService.class),
                mock(TokenCostService.class),
                new ObjectMapper(),
                new MemoryExtractionProperties(),
                Runnable::run);

        var facts = service.parseFacts("""
                {"facts":[
                  {"content":"用户偏好中文回答","memory_type":"PREFERENCE",
                   "entity_key":"user","predicate":"language","confidence":0.9},
                  {"content":"项目使用 Java 21","memory_type":"UNKNOWN",
                   "entity_key":"project","predicate":"runtime","confidence":0.8}
                ]}
                """);

        assertEquals(2, facts.size());
        assertEquals("PREFERENCE", facts.get(0).memoryType());
        assertEquals("FACT", facts.get(1).memoryType());
        assertEquals(0.9, facts.get(0).confidence());
    }
}
