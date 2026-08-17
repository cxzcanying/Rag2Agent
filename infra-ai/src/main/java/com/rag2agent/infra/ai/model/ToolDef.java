package com.rag2agent.infra.ai.model;

import java.util.Map;

/**
 * 工具声明（发给模型，描述可用工具）。
 * parameters 是 JSON Schema 风格的 Map，序列化后作为 function.parameters。
 */
public record ToolDef(String name, String description, Map<String, Object> parameters) {}
