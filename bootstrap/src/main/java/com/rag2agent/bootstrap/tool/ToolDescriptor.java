package com.rag2agent.bootstrap.tool;

import java.util.Map;

/**
 * 内部工具的统一描述。
 * parameters 是 JSON Schema 风格的 Map；requiresApproval 为 true 时该工具需人工审批后才能执行。
 */
public record ToolDescriptor(
        String name,
        String description,
        Map<String, Object> parameters,
        boolean requiresApproval) {}
