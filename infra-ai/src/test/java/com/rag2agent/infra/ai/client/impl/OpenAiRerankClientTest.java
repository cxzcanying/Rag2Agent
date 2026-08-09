package com.rag2agent.infra.ai.client.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rag2agent.infra.ai.config.AiProviderProperties.Provider;
import com.rag2agent.infra.ai.model.RerankRequest;
import com.rag2agent.infra.ai.model.RerankResponse;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiRerankClientTest {

    private MockWebServer server;
    private OpenAiRerankClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        Provider provider = new Provider();
        provider.setBaseUrl(server.url("/").toString());
        provider.setApiKey("test-key");
        provider.setRerankModel("test-rerank-model");
        client = new OpenAiRerankClient(provider);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void rerank_parsesResults() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"results": [
                          {"index": 1, "relevance_score": 0.95},
                          {"index": 0, "relevance_score": 0.80}
                        ]}
                        """));

        RerankResponse response = client.rerank(new RerankRequest(
                "siliconflow", null, "问题", List.of("文档A", "文档B"), 2));

        assertEquals(2, response.results().size());
        assertEquals(1, response.results().get(0).index());
        assertEquals(0.95, response.results().get(0).score());
        assertEquals(0, response.results().get(1).index());
    }
}
