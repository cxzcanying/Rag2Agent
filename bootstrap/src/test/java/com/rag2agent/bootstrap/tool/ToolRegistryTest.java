package com.rag2agent.bootstrap.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rag2agent.mcp.registry.McpToolRegistry;
import com.rag2agent.mcp.registry.McpToolRegistry.McpToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void adaptsRemoteReadOnlyToolWithoutChangingAgentLoop() {
        McpToolRegistry mcp = new McpToolRegistry() {
            @Override
            public List<McpToolDefinition> listTools() {
                return List.of(new McpToolDefinition(
                        "remote_lookup",
                        "远程只读查询",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("query", Map.of("type", "string")),
                                "required", List.of("query")),
                        false));
            }

            @Override
            public String execute(String toolName, Map<String, Object> arguments) {
                return toolName + ":" + arguments.get("query");
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(), Optional.of(mcp));

        assertEquals("remote_lookup", registry.toolDefs().getFirst().name());
        assertFalse(registry.requiresApproval("remote_lookup"));
        assertEquals("remote_lookup:RAG", registry.get("remote_lookup").execute(Map.of("query", "RAG")));
    }

    @Test
    void validatesRequiredFieldsAndTypesFromToolSchema() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("lookup")), Optional.empty());

        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> registry.validateArguments("lookup", Map.of()));
        IllegalArgumentException wrongType = assertThrows(
                IllegalArgumentException.class,
                () -> registry.validateArguments("lookup", Map.of("query", 1)));

        assertEquals("工具参数缺失: query", missing.getMessage());
        assertEquals("工具参数类型错误: query 应为 string", wrongType.getMessage());
    }

    @Test
    void fallsBackToLocalToolsWhenRemoteDiscoveryFails() {
        McpToolRegistry unavailable = new McpToolRegistry() {
            @Override
            public List<McpToolDefinition> listTools() {
                throw new IllegalStateException("unavailable");
            }

            @Override
            public String execute(String toolName, Map<String, Object> arguments) {
                throw new IllegalStateException("unavailable");
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(tool("local_lookup")), Optional.of(unavailable));

        assertEquals(List.of("local_lookup"), registry.toolDefs().stream().map(def -> def.name()).toList());
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(
                        name,
                        "查询",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("query", Map.of("type", "string")),
                                "required", List.of("query")),
                        false);
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                return String.valueOf(arguments.get("query"));
            }
        };
    }
}
