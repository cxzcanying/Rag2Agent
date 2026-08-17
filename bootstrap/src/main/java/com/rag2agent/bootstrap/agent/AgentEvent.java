package com.rag2agent.bootstrap.agent;

/**
 * 对话过程中推送给前端的结构化事件。
 * type: reference / tool_start / approval_required / done / error
 */
public record AgentEvent(String type, Object data) {}
