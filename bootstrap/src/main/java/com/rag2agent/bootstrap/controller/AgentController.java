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
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public void chat(@RequestBody @Valid ChatRequest request, HttpServletResponse response) throws IOException {
        Long userId = StpUtil.getLoginIdAsLong();
        String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? UUID.randomUUID().toString()
                : request.sessionId();
        // 手动写 SSE 流：每个事件立即 flush，方法正常结束后 Tomcat 发送完整的 chunked 终止块，
        // 客户端 fetch 才能拿到"流正常结束"而不是"连接被掐断"。
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        PrintWriter writer = response.getWriter();

        try {
            agentRunService.start(
                    userId, sessionId, request.query(), request.kbId(),
                    event -> sendEvent(writer, event));
        } catch (Exception e) {
            // 响应可能已部分提交，只能以 SSE 事件形式返回错误
            sendEvent(writer, new AgentEvent("error", e.getMessage()));
        }
        writer.flush();
    }

    @PostMapping("/agent/approvals/{runId}")
    public ApiResponse<AgentExecutionResult> approve(
            @PathVariable Long runId, @RequestBody ApprovalRequest request) {
        AgentExecutionResult result = agentRunService.approve(runId, request.approved(), event -> {});
        return ApiResponse.success(result);
    }

    private void sendEvent(PrintWriter writer, AgentEvent event) {
        try {
            writer.write("event:message\n");
            writer.write("data:" + objectMapper.writeValueAsString(event) + "\n\n");
            writer.flush();
        } catch (Exception ignored) {
            // 客户端断开时忽略，避免影响主流程
        }
    }
}
