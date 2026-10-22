package com.rag2agent.bootstrap.tool.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.Neo4jGraphProperties;
import com.rag2agent.bootstrap.graph.GraphCommunityService;
import com.rag2agent.bootstrap.graph.KnowledgeGraphRepository.CommunitySummary;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchGlobalGraphToolTest {

    @Test
    void returnsCommunitySummariesWhenEnabled() {
        GraphCommunityService communities = mock(GraphCommunityService.class);
        Neo4jGraphProperties properties = new Neo4jGraphProperties();
        properties.setEnabled(true);
        when(communities.globalSummaries(3L, 10))
                .thenReturn(List.of(new CommunitySummary(1L, "云计算收入增长")));
        SearchGlobalGraphTool tool = new SearchGlobalGraphTool(
                mock(KnowledgeBaseService.class), communities, properties, new ObjectMapper());

        String result = tool.execute(Map.of("kb_id", 3));

        assertTrue(result.contains("云计算收入增长"));
    }
}
