package com.rag2agent.bootstrap.agent;

import java.util.List;

/**
 * 一次 Agent 执行的结果。
 * 若状态为 WAITING_APPROVAL，pendingApprovalToolCallId 指向待审批的工具调用。
 */
public record AgentExecutionResult(
        Long runId,
        String status,
        String answer,
        List<Reference> references,
        Long pendingApprovalToolCallId) {}
