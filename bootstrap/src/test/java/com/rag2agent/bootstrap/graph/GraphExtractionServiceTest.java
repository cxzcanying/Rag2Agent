package com.rag2agent.bootstrap.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.infra.ai.client.ChatModelClient;
import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import com.rag2agent.rag.core.document.StructuredDocument;
import com.rag2agent.rag.core.document.StructuredDocument.Element;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphExtractionServiceTest {

    @Test
    void keepsOnlyEntitiesAndRelationsWithDocumentEvidence() {
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
                return new ChatCompletionResponse("""
                        ```json
                        {"entities":[
                          {"name":"RAG2Agent","type":"系统","evidence":"texts-0","confidence":0.9},
                          {"name":"虚构实体","type":"other","evidence":"unknown","confidence":0.9}
                        ],"relations":[
                          {"source":"RAG2Agent","target":"RAG2Agent","type":"self","evidence":"texts-0","confidence":0.8}
                        ]}
                        ```
                        """, "stop", Map.of());
            }

            @Override
            public void stream(
                    ChatCompletionRequest request,
                    java.util.function.Consumer<String> onDelta) {
                throw new UnsupportedOperationException();
            }
        };
        StructuredDocument document = new StructuredDocument(
                "doc-1",
                "RAG2Agent 是企业知识库项目",
                "",
                List.of("RAG2Agent 是企业知识库项目"),
                List.of(new Element(
                        "texts-0", null, "paragraph", 0, 1, 1, null,
                        "RAG2Agent 是企业知识库项目", Map.of())),
                List.of(),
                List.of(),
                Map.of());

        var result = new GraphExtractionService(chat, new ObjectMapper()).extract(document);

        assertEquals(1, result.entities().size());
        assertEquals("RAG2Agent", result.entities().getFirst().name());
        assertEquals(1, result.relations().size());
        assertEquals("texts-0", result.relations().getFirst().evidence());
    }
}
