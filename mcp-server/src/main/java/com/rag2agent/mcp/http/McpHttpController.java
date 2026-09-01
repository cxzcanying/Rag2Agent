package com.rag2agent.mcp.http;

import com.rag2agent.mcp.config.McpServerProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 最小 MCP JSON-RPC HTTP 端点，支持 initialize、tools/list、tools/call。 */
@RestController
@RequestMapping("/mcp")
@EnableConfigurationProperties(McpServerProperties.class)
public class McpHttpController {

    private final McpServerProperties properties;

    public McpHttpController(McpServerProperties properties) {
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> handle(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-MCP-Scopes", required = false) String scopeHeader,
            @RequestBody Map<String, Object> request) {
        if (!authorized(authorization, scopeHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(request, -32001, "MCP authorization failed"));
        }
        String method = String.valueOf(request.get("method"));
        Object id = request.get("id");
        return switch (method) {
            case "initialize" -> ResponseEntity.ok(result(id, Map.of(
                    "protocolVersion", "2025-06-18",
                    "serverInfo", Map.of("name", "rag2agent-mcp", "version", "1.0"),
                    "capabilities", Map.of("tools", Map.of()))));
            case "tools/list" -> ResponseEntity.ok(result(id, Map.of("tools", List.of(
                    Map.of("name", "echo", "description", "Return text from the remote MCP server",
                            "inputSchema", Map.of("type", "object", "required", List.of("text"),
                                    "properties", Map.of("text", Map.of("type", "string"))))))));
            case "tools/call" -> call(id, request);
            default -> ResponseEntity.ok(error(request, -32601, "Method not found: " + method));
        };
    }

    private ResponseEntity<Map<String, Object>> call(Object id, Map<String, Object> request) {
        Map<?, ?> params = request.get("params") instanceof Map<?, ?> value ? value : Map.of();
        String name = String.valueOf(params.get("name"));
        if (!"echo".equals(name) || !scopes(properties.getTokenScopes()).contains("mcp:read")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(request, -32003, "MCP tool permission denied"));
        }
        Map<?, ?> arguments = params.get("arguments") instanceof Map<?, ?> value ? value : Map.of();
        Object text = arguments.get("text");
        if (!(text instanceof String value) || value.isBlank()) {
            return ResponseEntity.ok(error(request, -32602, "text is required"));
        }
        return ResponseEntity.ok(result(id, Map.of("content", List.of(Map.of("type", "text", "text", value)),
                "isError", false)));
    }

    private boolean authorized(String authorization, String scopeHeader) {
        return authorization != null && authorization.equals("Bearer " + properties.getToken())
                && scopes(properties.getTokenScopes()).containsAll(properties.getRequiredScopes())
                && scopes(scopeHeader).containsAll(properties.getRequiredScopes());
    }

    private Set<String> scopes(List<String> values) {
        return values == null ? Set.of() : values.stream().map(String::trim)
                .filter(value -> !value.isBlank()).collect(Collectors.toSet());
    }

    private Set<String> scopes(String header) {
        return header == null ? Set.of() : java.util.Arrays.stream(header.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toSet());
    }

    private Map<String, Object> result(Object id, Object result) {
        return Map.of("jsonrpc", "2.0", "id", id, "result", result);
    }

    private Map<String, Object> error(Map<String, Object> request, int code, String message) {
        return Map.of("jsonrpc", "2.0", "id", request.get("id"),
                "error", Map.of("code", code, "message", message));
    }
}
