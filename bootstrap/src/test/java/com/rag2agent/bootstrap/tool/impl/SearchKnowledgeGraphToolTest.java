package com.rag2agent.bootstrap.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.Neo4jGraphProperties;
import com.rag2agent.bootstrap.graph.KnowledgeGraphRepository;
import com.rag2agent.bootstrap.graph.KnowledgeGraphRepository.GraphPath;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchKnowledgeGraphToolTest {

    @Test
    void returnsPathsWhenEnabledAndMarksFallbackWhenDisabled() {
        KnowledgeGraphRepository repository = mock(KnowledgeGraphRepository.class);
        Neo4jGraphProperties properties = new Neo4jGraphProperties();
        properties.setEnabled(true);
        when(repository.searchPaths(3L, "RAG2Agent", 10)).thenReturn(List.of(
                new GraphPath("RAG2Agent", List.of("USES"), "PostgreSQL",
                        List.of("RAG2Agent", "PostgreSQL"), 0.8)));
        SearchKnowledgeGraphTool tool = new SearchKnowledgeGraphTool(
                mock(KnowledgeBaseService.class), repository, properties, new ObjectMapper());

        String enabled = tool.execute(Map.of("kb_id", 3, "query", "RAG2Agent"));
        assertTrue(enabled.contains("PostgreSQL"));

        properties.setEnabled(false);
        String disabled = tool.execute(Map.of("kb_id", 3, "query", "RAG2Agent"));
        assertEquals("{\"enabled\":false,\"paths\":[],\"fallback\":\"text_search\"}", disabled);
    }
}
