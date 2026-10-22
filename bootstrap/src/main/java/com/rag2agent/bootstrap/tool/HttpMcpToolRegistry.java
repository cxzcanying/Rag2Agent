package com.rag2agent.bootstrap.tool;

import com.rag2agent.bootstrap.config.McpRemoteProperties;
import com.rag2agent.mcp.registry.McpToolRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** MCP 2.0 Streamable HTTP 客户端；远程服务异常时由 ToolRegistry 保留本地工具。 */
@Component
@ConditionalOnProperty(prefix = "rag2agent.mcp.remote", name = "enabled", havingValue = "true")
public class HttpMcpToolRegistry implements McpToolRegistry {

    private static final int MAX_PAGES = 20;

    private final McpRemoteProperties properties;
    private volatile McpSyncClient client;

    public HttpMcpToolRegistry(McpRemoteProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<McpToolDefinition> listTools() {
        List<McpToolDefinition> definitions = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            McpSchema.ListToolsResult result =
                    cursor == null ? client().listTools() : client().listTools(cursor);
            result.tools().forEach(tool -> definitions.add(new McpToolDefinition(
                    tool.name(),
                    tool.description() == null ? "" : tool.description(),
                    tool.inputSchema(),
                    !Boolean.TRUE.equals(
                            tool.annotations() == null ? null : tool.annotations().readOnlyHint()))));
            cursor = result.nextCursor();
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        return List.copyOf(definitions);
    }

    @Override
    public String execute(String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client().callTool(
                McpSchema.CallToolRequest.builder(toolName)
                        .arguments(arguments)
                        .build());
        String content = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("");
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("MCP 工具执行失败: " + content);
        }
        return content;
    }

    private McpSyncClient client() {
        McpSyncClient current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                client = createClient();
                client.initialize();
            }
            return client;
        }
    }

    private McpSyncClient createClient() {
        URI endpoint = URI.create(properties.getEndpoint());
        String baseUrl = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
        String path = endpoint.getRawPath() == null || endpoint.getRawPath().isBlank()
                ? "/mcp"
                : endpoint.getRawPath();
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + properties.getToken())
                .header("X-MCP-Scopes", properties.getScopes());
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder(baseUrl)
                        .endpoint(path)
                        .requestBuilder(requestBuilder)
                        .connectTimeout(Duration.ofSeconds(3))
                        .build();
        return McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation.builder("rag2agent", "0.1.0").build())
                .requestTimeout(Duration.ofSeconds(10))
                .build();
    }

    @PreDestroy
    public void close() {
        McpSyncClient current = client;
        if (current != null) {
            current.closeGracefully();
        }
    }
}
