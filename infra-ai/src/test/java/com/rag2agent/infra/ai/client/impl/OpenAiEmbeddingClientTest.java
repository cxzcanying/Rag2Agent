package com.rag2agent.infra.ai.client.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rag2agent.infra.ai.config.AiProviderProperties.Provider;
import com.rag2agent.infra.ai.model.EmbeddingRequest;
import com.rag2agent.infra.ai.model.EmbeddingResponse;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiEmbeddingClientTest {

    private MockWebServer server;
    private OpenAiEmbeddingClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        Provider provider = new Provider();
        provider.setBaseUrl(server.url("/").toString());
        provider.setApiKey("test-key");
        provider.setEmbeddingModel("test-embed-model");
        client = new OpenAiEmbeddingClient(provider);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void embed_parsesVectors() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"data": [
                          {"embedding": [0.1, 0.2, 0.3], "index": 0},
                          {"embedding": [0.4, 0.5], "index": 1}
                        ]}
                        """));

        EmbeddingResponse response = client.embed(
                new EmbeddingRequest("siliconflow", null, List.of("文本一", "文本二")));

        assertEquals(2, response.vectors().size());
        assertEquals(List.of(0.1f, 0.2f, 0.3f), response.vectors().get(0));
        assertEquals(List.of(0.4f, 0.5f), response.vectors().get(1));
    }
}
