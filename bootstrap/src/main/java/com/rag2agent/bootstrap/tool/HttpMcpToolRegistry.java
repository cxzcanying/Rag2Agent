package com.rag2agent.bootstrap.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.McpRemoteProperties;
import com.rag2agent.mcp.registry.McpToolRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** MCP Streamable HTTP 客户端，服务不可用时由 ToolRegistry 保留本地工具。 */
@Component
@ConditionalOnProperty(prefix = "rag2agent.mcp.remote", name = "enabled", havingValue = "true")
public class HttpMcpToolRegistry implements McpToolRegistry {

    private final ObjectMapper objectMapper;
    private final McpRemoteProperties properties;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public HttpMcpToolRegistry(ObjectMapper objectMapper, McpRemoteProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public List<McpToolDefinition> listTools() {
        call("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "rag2agent", "version", "0.1.0")));
        Map<String, Object> result = call("tools/list", Map.of());
        List<Map<String, Object>> tools = objectMapper.convertValue(
                result.getOrDefault("tools", List.of()), new TypeReference<>() {});
        List<McpToolDefinition> definitions = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            definitions.add(new McpToolDefinition(String.valueOf(tool.get("name")),
                    String.valueOf(tool.getOrDefault("description", "")),
                    objectMapper.convertValue(tool.getOrDefault("inputSchema", Map.of()), new TypeReference<>() {}),
                    false));
        }
        return definitions;
    }

    @Override
    public String execute(String toolName, Map<String, Object> arguments) {
        Map<String, Object> result = call("tools/call", Map.of("name", toolName, "arguments", arguments));
        List<Map<String, Object>> content = objectMapper.convertValue(result.getOrDefault("content", List.of()), new TypeReference<>() {});
        return content.stream().map(item -> String.valueOf(item.getOrDefault("text", ""))).findFirst().orElse("");
    }

    private Map<String, Object> call(String method, Map<String, Object> params) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", System.nanoTime());
            request.put("method", method);
            request.put("params", params);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(properties.getEndpoint()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + properties.getToken())
                    .header("X-MCP-Scopes", properties.getScopes())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("MCP HTTP " + response.statusCode());
            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            if (body.containsKey("error")) throw new IllegalStateException("MCP error: " + body.get("error"));
            return objectMapper.convertValue(body.get("result"), new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("MCP 远程调用失败", exception);
        }
    }
}
