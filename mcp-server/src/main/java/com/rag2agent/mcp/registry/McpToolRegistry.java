package com.rag2agent.mcp.registry;

import java.util.List;
import java.util.Map;

/**
 * 远程 MCP transport 的最小边界。具体网络协议由实现负责，bootstrap 只消费工具描述并发起调用。
 */
public interface McpToolRegistry {

    List<McpToolDefinition> listTools();

    String execute(String toolName, Map<String, Object> arguments);

    record McpToolDefinition(
            String name,
            String description,
            Map<String, Object> parameters,
            boolean requiresApproval) {}
}
