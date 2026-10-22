package com.rag2agent.infra.ai.client.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rag2agent.infra.ai.config.AiProviderProperties.Provider;
import com.rag2agent.infra.ai.config.AiResilienceProperties;
import com.rag2agent.infra.ai.model.VisionRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiVisionClientTest {

    private MockWebServer server;
    private OpenAiVisionClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        Provider provider = new Provider();
        provider.setName("qwen-vl");
        provider.setBaseUrl(server.url("/").toString());
        provider.setApiKey("test-key");
        provider.setVisionModel("qwen3-vl-flash");
        client = new OpenAiVisionClient(
                provider, new AiResilienceProperties(), new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void sendsImageDataAndParsesDescription() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"choices":[{"message":{"content":"图片显示收入增长。"}}]}
                        """));

        var result = client.describe(new VisionRequest(
                "描述图片", new byte[] {1, 2, 3}, "image/png"));

        assertEquals("图片显示收入增长。", result.content());
        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("data:image/png;base64,AQID"));
        assertTrue(body.contains("qwen3-vl-flash"));
    }
}
