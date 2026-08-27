package com.rag2agent.bootstrap.tool;

import com.rag2agent.infra.ai.model.ToolDef;
import com.rag2agent.mcp.registry.McpToolRegistry;
import com.rag2agent.mcp.registry.McpToolRegistry.McpToolDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工具注册表：聚合所有内部工具，统一转成发给模型的 ToolDef 列表。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools;

    public ToolRegistry(List<Tool> toolList, Optional<McpToolRegistry> mcpRegistry) {
        this.tools = new LinkedHashMap<>();
        toolList.forEach(this::register);
        mcpRegistry.ifPresent(this::registerRemoteTools);
    }

    public List<ToolDef> toolDefs() {
        return tools.values().stream()
                .map(tool -> new ToolDef(
                        tool.descriptor().name(),
                        tool.descriptor().description(),
                        tool.descriptor().parameters()))
                .toList();
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public void validateArguments(String name, Map<String, Object> arguments) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未知工具: " + name);
        }
        Map<String, Object> schema = tool.descriptor().parameters();
        Object requiredValue = schema.get("required");
        if (requiredValue instanceof List<?> required) {
            required.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(field -> !arguments.containsKey(field) || arguments.get(field) == null)
                    .findFirst()
                    .ifPresent(field -> {
                        throw new IllegalArgumentException("工具参数缺失: " + field);
                    });
        }
        Object propertiesValue = schema.get("properties");
        if (!(propertiesValue instanceof Map<?, ?> properties)) {
            return;
        }
        arguments.forEach((field, value) -> {
            Object fieldSchema = properties.get(field);
            if (value == null || !(fieldSchema instanceof Map<?, ?> definition)) {
                return;
            }
            Object type = definition.get("type");
            if (type instanceof String expected && !matchesType(value, expected)) {
                throw new IllegalArgumentException("工具参数类型错误: " + field + " 应为 " + expected);
            }
        });
    }

    public boolean requiresApproval(String name) {
        Tool tool = tools.get(name);
        return tool != null && tool.descriptor().requiresApproval();
    }

    private void register(Tool tool) {
        ToolDescriptor descriptor = tool.descriptor();
        if (descriptor == null || descriptor.name() == null || descriptor.name().isBlank()
                || descriptor.parameters() == null) {
            throw new IllegalArgumentException("工具描述无效");
        }
        if (tools.putIfAbsent(descriptor.name(), tool) != null) {
            throw new IllegalStateException("工具名称重复: " + descriptor.name());
        }
    }

    private void registerRemoteTools(McpToolRegistry registry) {
        List<McpToolDefinition> definitions;
        try {
            definitions = registry.listTools();
        } catch (RuntimeException exception) {
            log.warn("MCP 工具发现失败，继续使用本地工具: exceptionType={}",
                    exception.getClass().getName());
            return;
        }
        if (definitions != null) {
            definitions.stream()
                    .map(definition -> new McpToolAdapter(registry, definition))
                    .forEach(this::register);
        }
    }

    private static boolean matchesType(Object value, String expected) {
        return switch (expected) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> true;
        };
    }

    private record McpToolAdapter(McpToolRegistry registry, McpToolDefinition definition) implements Tool {

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    definition.name(),
                    definition.description(),
                    definition.parameters(),
                    definition.requiresApproval());
        }

        @Override
        public String execute(Map<String, Object> arguments) {
            return registry.execute(definition.name(), arguments);
        }
    }
}
