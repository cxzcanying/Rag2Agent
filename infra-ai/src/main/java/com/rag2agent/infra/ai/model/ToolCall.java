package com.rag2agent.infra.ai.model;

/**
 * 模型返回的一次工具调用。
 * arguments 是 JSON 字符串（OpenAI 兼容协议里 function.arguments 是字符串，不是对象）。
 */
public record ToolCall(String id, String type, String name, String arguments) {}
