package com.rag2agent.infra.ai.model;

import java.util.List;

/**
 * 对话消息。除纯文本外，还支持：
 * - assistant 消息携带 tool_calls（模型要求调用工具）；
 * - tool 消息携带 tool_call_id + name（工具执行结果回传）。
 */
public record ChatMessage(
        String role,
        String content,
        String name,
        String toolCallId,
        List<ToolCall> toolCalls) {

    public ChatMessage(String role, String content) {
        this(role, content, null, null, List.of());
    }

    public static ChatMessage tool(String toolCallId, String name, String content) {
        return new ChatMessage("tool", content, name, toolCallId, List.of());
    }

    public static ChatMessage assistantWithToolCalls(List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", null, null, null, toolCalls);
    }
}
