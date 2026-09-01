package com.rag2agent.bootstrap.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.agent.AgentEvent;
import com.rag2agent.bootstrap.agent.AgentExecutionResult;
import com.rag2agent.bootstrap.agent.AgentRunService;
import com.rag2agent.bootstrap.dto.AgentDtos.ApprovalRequest;
import com.rag2agent.bootstrap.dto.AgentDtos.ChatRequest;
import com.rag2agent.framework.common.ApiResponse;
import com.rag2agent.infra.ai.exception.AiClientException;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.web.bind.annotation.RequestHeader;
import com.rag2agent.bootstrap.agent.AgentEventReplayStore;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 对话接口：POST /api/chat 走 SSE 推送结构化事件；审批接口同步返回结果。
 * @author 21311
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private final AgentRunService agentRunService;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final AgentEventReplayStore eventStore;

    public AgentController(AgentRunService agentRunService, ObjectMapper objectMapper, Tracer tracer,
            AgentEventReplayStore eventStore) {
        this.agentRunService = agentRunService;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.eventStore = eventStore;
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
        Span currentSpan = tracer.currentSpan();
        //增加trace事件，返回X-Trace-Id
        String traceId = currentSpan == null ? null : currentSpan.context().traceId();
        AtomicReference<AgentEvent> pendingTraceEvent = new AtomicReference<>();
        if (traceId != null) {
            response.setHeader("X-Trace-Id", traceId);
            AgentEvent traceEvent = new AgentEvent("trace", Map.of("traceId", traceId));
            pendingTraceEvent.set(traceEvent);
            sendEvent(writer, traceEvent);
        }

        AtomicLong runId = new AtomicLong();
        try {
            agentRunService.start(
                    userId, sessionId, request.clientRequestId(), request.query(), request.kbId(),
                    event -> {
                        if ("run".equals(event.type()) && event.data() instanceof Map<?, ?> data
                                && data.get("runId") instanceof Number id) {
                            runId.set(id.longValue());
                            AgentEvent traceEvent = pendingTraceEvent.getAndSet(null);
                            if (traceEvent != null) {
                                try {
                                    eventStore.append(runId.get(), traceEvent);
                                } catch (RuntimeException ignored) {
                                    // Redis 故障不能中断当前 Agent；实时事件已经发送。
                                }
                            }
                        }
                        appendAndSend(runId.get(), writer, event);
                    });
        } catch (Exception e) {
            // 响应可能已部分提交，只能以 SSE 事件形式返回错误
            if (e instanceof AiClientException aiException) {
                appendAndSend(runId.get(), writer, new AgentEvent("error", Map.of(
                        "code", aiException.kind() == AiClientException.Kind.RATE_LIMITED ? "429" : "502",
                        "message", aiErrorMessage(aiException))));
            } else {
                appendAndSend(runId.get(), writer, new AgentEvent("error", "请求执行失败"));
            }
        }
        writer.flush();
    }

    @GetMapping(value = "/agent/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void replay(
            @PathVariable Long runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            HttpServletResponse response) throws IOException {
        Long userId = StpUtil.getLoginIdAsLong();
        agentRunService.getRun(userId, runId);
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        PrintWriter writer = response.getWriter();
        String cursor = lastEventId;
        for (int round = 0; round < 120; round++) {
            boolean emitted = false;
            for (AgentEventReplayStore.StoredEvent stored : eventStore.readAfter(runId, cursor, 100)) {
                try {
                    AgentEvent event = objectMapper.readValue(stored.payload(), AgentEvent.class);
                    sendEvent(writer, event, stored.id());
                    cursor = stored.id();
                    emitted = true;
                } catch (Exception ignored) {
                    // 坏事件不阻塞后续事件回放。
                }
            }
            AgentExecutionResult result = agentRunService.getRun(userId, runId);
            if (isTerminal(result.status()) && !emitted) {
                break;
            }
            if (!emitted) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        writer.flush();
    }

    @PostMapping("/agent/approvals/{runId}")
    public ApiResponse<AgentExecutionResult> approve(
            @PathVariable Long runId, @RequestBody ApprovalRequest request) {
        AgentExecutionResult result = agentRunService.approve(runId, request.approved(), event -> {});
        return ApiResponse.success(result);
    }

    @GetMapping("/agent/runs/{runId}")
    public ApiResponse<AgentExecutionResult> getRun(@PathVariable Long runId) {
        return ApiResponse.success(agentRunService.getRun(StpUtil.getLoginIdAsLong(), runId));
    }

    private void sendEvent(PrintWriter writer, AgentEvent event) {
        sendEvent(writer, event, null);
    }

    private void appendAndSend(long runId, PrintWriter writer, AgentEvent event) {
        String eventId = null;
        if (runId > 0) {
            try {
                eventId = eventStore.append(runId, event);
            } catch (RuntimeException ignored) {
                // Redis 故障不能中断当前 Agent；客户端仍可收到实时事件。
            }
        }
        sendEvent(writer, event, eventId);
    }

    private void sendEvent(PrintWriter writer, AgentEvent event, String eventId) {
        try {
            if (eventId != null) {
                writer.write("id:" + eventId + "\n");
            }
            writer.write("event:message\n");
            writer.write("data:" + objectMapper.writeValueAsString(event) + "\n\n");
            writer.flush();
        } catch (Exception ignored) {
            // 客户端断开时忽略，避免影响主流程
        }
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "DEGRADED".equals(status)
                || "MAX_STEPS_REACHED".equals(status) || "FAILED".equals(status)
                || "CANCELLED".equals(status);
    }

    private String aiErrorMessage(AiClientException exception) {
        return switch (exception.kind()) {
            case TIMEOUT -> "模型服务响应超时，请稍后重试";
            case RATE_LIMITED -> "模型服务限流，请稍后重试";
            case UPSTREAM_ERROR -> "模型服务暂时不可用，请稍后重试";
            case CLIENT_ERROR -> "模型请求参数无效";
            case CIRCUIT_OPEN -> "模型服务熔断中，请稍后重试";
            case BULKHEAD_REJECTED -> "模型并发已达上限，请稍后重试";
        };
    }
}
