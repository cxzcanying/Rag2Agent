package com.rag2agent.bootstrap.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rag2agent.bootstrap.config.Neo4jGraphProperties;
import com.rag2agent.bootstrap.graph.KnowledgeGraphRepository.CommunityEntities;
import com.rag2agent.infra.ai.client.ChatModelClient;
import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphCommunityServiceTest {

    @Test
    void summarizesEachCommunityAndPersistsIt() {
        Neo4jGraphProperties properties = new Neo4jGraphProperties();
        properties.setEnabled(true);
        KnowledgeGraphRepository repository = mock(KnowledgeGraphRepository.class);
        when(repository.communities(3L, 20)).thenReturn(List.of(
                new CommunityEntities(1L, List.of("RAG2Agent", "PostgreSQL"), 2)));
        ChatModelClient chat = new ChatModelClient() {
            @Override
            public String providerName() {
                return "test";
            }

            @Override
            public String modelName() {
                return "test";
            }

            @Override
            public ChatCompletionResponse complete(ChatCompletionRequest request) {
                return new ChatCompletionResponse("企业 RAG 与数据库", "stop", Map.of());
            }

            @Override
            public void stream(
                    ChatCompletionRequest request,
                    java.util.function.Consumer<String> onDelta) {
                throw new UnsupportedOperationException();
            }
        };

        int count = new GraphCommunityService(properties, repository, chat).refresh(3L);

        assertEquals(1, count);
        org.mockito.Mockito.verify(repository).saveCommunitySummary(3L, 1L, "企业 RAG 与数据库");
    }
}
