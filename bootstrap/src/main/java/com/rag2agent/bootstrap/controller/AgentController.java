package com.rag2agent.bootstrap.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.agent.AgentEvent;
import com.rag2agent.bootstrap.agent.AgentExecutionResult;
import com.rag2agent.bootstrap.agent.AgentRunService;
import com.rag2agent.bootstrap.dto.AgentDtos.ApprovalRequest;
import com.rag2agent.bootstrap.dto.AgentDtos.ChatRequest;
import com.rag2agent.framework.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 对话接口：POST /api/chat 走 SSE 推送结构化事件；审批接口同步返回结果。
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private final AgentRunService agentRunService;
    private final ObjectMapper objectMapper;

    public AgentController(AgentRunService agentRunService, ObjectMapper objectMapper) {
        this.agentRunService = agentRunService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody @Valid ChatRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? UUID.randomUUID().toString()
                : request.sessionId();
        SseEmitter emitter = new SseEmitter(180_000L);

        try {
            agentRunService.start(
                    userId, sessionId, request.query(), request.kbId(),
                    event -> sendEvent(emitter, event));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @PostMapping("/agent/approvals/{runId}")
    public ApiResponse<AgentExecutionResult> approve(
            @PathVariable Long runId, @RequestBody ApprovalRequest request) {
        AgentExecutionResult result = agentRunService.approve(runId, request.approved(), event -> {});
        return ApiResponse.success(result);
    }

    private void sendEvent(SseEmitter emitter, AgentEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event().name("message").data(json, MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // 客户端断开时忽略，避免影响主流程
        }
    }
}
