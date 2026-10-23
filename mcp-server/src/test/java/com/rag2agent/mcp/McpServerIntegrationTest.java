package com.rag2agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = McpServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "rag2agent.mcp.server.enabled=true",
            "rag2agent.mcp.server.required-scopes=mcp:read",
            "rag2agent.mcp.server.clients[0].name=integration-test",
            "rag2agent.mcp.server.clients[0].token=test-token",
            "rag2agent.mcp.server.clients[0].scopes[0]=mcp:read"
        })
class McpServerIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void officialClientNegotiatesAndCallsTool() {
        HttpRequest.Builder headers = HttpRequest.newBuilder()
                .header("Authorization", "Bearer test-token");
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .requestBuilder(headers)
                        .build();
        var client = McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation.builder("integration-test", "1.0.0").build())
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        try {
            client.initialize();
            assertTrue(client.listTools().tools().stream().anyMatch(tool -> "echo".equals(tool.name())));
            assertFalse(client.listResources().resources().isEmpty());
            assertFalse(client.listPrompts().prompts().isEmpty());

            McpSchema.CallToolResult result = client.callTool(
                    McpSchema.CallToolRequest.builder("echo")
                            .arguments(Map.of("text", "hello mcp"))
                            .build());

            assertFalse(result.isError());
            assertEquals("hello mcp", ((McpSchema.TextContent) result.content().getFirst()).text());
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    void missingOrInvalidBearerIsRejected() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest missingToken = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> unauthorized = http.send(missingToken, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthorized.statusCode());

        HttpRequest invalidToken = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer wrong-token")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> rejected = http.send(invalidToken, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, rejected.statusCode());
    }
}
