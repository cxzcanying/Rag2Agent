package com.rag2agent.bootstrap.agent;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.entity.AgentRun;
import com.rag2agent.bootstrap.entity.AgentStep;
import com.rag2agent.bootstrap.entity.ToolCallRecord;
import com.rag2agent.bootstrap.mapper.AgentRunMapper;
import com.rag2agent.bootstrap.mapper.AgentStepMapper;
import com.rag2agent.bootstrap.mapper.ToolCallRecordMapper;
import com.rag2agent.bootstrap.service.HybridSearchService;
import com.rag2agent.bootstrap.tool.Tool;
import com.rag2agent.bootstrap.tool.ToolRegistry;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import com.rag2agent.infra.ai.client.ChatModelClient;
import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import com.rag2agent.infra.ai.model.ChatMessage;
import com.rag2agent.infra.ai.model.ToolCall;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Agent 执行状态机：INIT -> ROUTING -> EXECUTING -> WAITING_APPROVAL -> FINALIZING -> COMPLETED / FAILED。
 *
 * 编排：先主动检索（无引用不答）→ function calling 循环（模型决定调工具）→ 高风险工具挂起等审批。
 * 审批挂起时把消息历史存 Redis，审批通过后恢复并继续循环。
 */
@Service
public class AgentRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);
    private static final Duration MESSAGE_TTL = Duration.ofMinutes(30);
    private static final int DEFAULT_TOP_K = 5;
    private static final int DEFAULT_MAX_ITERATIONS = 10;

    private static final String SYSTEM_PROMPT = """
            你是一个企业知识库问答助手。回答必须基于检索到的知识库内容，并标注引用编号（如 [1]）。
            如果检索结果不足以回答，如实说明"未找到相关资料"，不要编造。
            当用户要求删除文档时，调用 delete_document 工具；该操作需要人工审批。
            """;

    private final ChatModelClient chatClient;
    private final HybridSearchService searchService;
    private final ToolRegistry toolRegistry;
    private final AgentRunMapper runMapper;
    private final AgentStepMapper stepMapper;
    private final ToolCallRecordMapper toolCallMapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;

    public AgentRunService(
            ChatModelClient chatClient,
            HybridSearchService searchService,
            ToolRegistry toolRegistry,
            AgentRunMapper runMapper,
            AgentStepMapper stepMapper,
            ToolCallRecordMapper toolCallMapper,
            ObjectMapper objectMapper,
            StringRedisTemplate redis) {
        this.chatClient = chatClient;
        this.searchService = searchService;
        this.toolRegistry = toolRegistry;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.toolCallMapper = toolCallMapper;
        this.objectMapper = objectMapper;
        this.redis = redis;
    }

    public AgentExecutionResult start(
            Long userId, String sessionId, String query, Long kbId, Consumer<AgentEvent> onEvent) {
        AgentRun run = new AgentRun();
        run.setSessionId(sessionId);
        run.setUserId(userId);
        run.setStatus("INIT");
        run.setQuery(query);
        run.setMaxIterations(DEFAULT_MAX_ITERATIONS);
        runMapper.insert(run);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", SYSTEM_PROMPT));
        messages.add(new ChatMessage("user", query));

        updateStatus(run.getId(), "ROUTING");
        List<Reference> references = searchAndRecord(run.getId(), kbId, query, onEvent);
        if (references.isEmpty()) {
            // 无引用不答：不把空上下文交给模型，直接返回未找到
            updateStatus(run.getId(), "COMPLETED");
            String answer = "未找到相关资料，无法回答该问题。";
            onEvent.accept(new AgentEvent("done", Map.of("answer", answer, "references", List.of())));
            return new AgentExecutionResult(run.getId(), "COMPLETED", answer, List.of(), null);
        }
        messages.add(new ChatMessage("user", buildContext(query, references)));
        saveMessages(run.getId(), messages);
        saveReferences(run.getId(), references);

        return executeLoop(run.getId(), messages, references, onEvent);
    }

    public AgentExecutionResult approve(Long runId, boolean approved, Consumer<AgentEvent> onEvent) {
        AgentRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent 执行不存在");
        }
        if (!"WAITING_APPROVAL".equals(run.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前执行不在待审批状态");
        }
        ToolCallRecord pending = toolCallMapper.listByRunId(runId).stream()
                .filter(call -> "WAITING_APPROVAL".equals(call.getStatus()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "待审批工具调用不存在"));

        List<ChatMessage> messages = loadMessages(runId);
        List<Reference> references = loadReferences(runId);
        String toolCallId = String.valueOf(pending.getId());

        if (approved) {
            String output;
            try {
                output = executeTool(pending.getToolName(), parseArguments(pending.getInput()));
                pending.setStatus("SUCCEEDED");
                pending.setOutput(toJson(output));
            } catch (Exception e) {
                output = "工具执行失败: " + e.getMessage();
                pending.setStatus("FAILED");
                pending.setErrorMessage(e.getMessage());
            }
            toolCallMapper.updateResult(pending);
            messages.add(ChatMessage.assistantWithToolCalls(List.of(
                    new ToolCall(toolCallId, "function", pending.getToolName(), pending.getInput()))));
            messages.add(ChatMessage.tool(toolCallId, pending.getToolName(), output));
        } else {
            pending.setStatus("REJECTED");
            pending.setOutput(toJson("用户拒绝执行"));
            toolCallMapper.updateResult(pending);
            messages.add(ChatMessage.assistantWithToolCalls(List.of(
                    new ToolCall(toolCallId, "function", pending.getToolName(), pending.getInput()))));
            messages.add(ChatMessage.tool(toolCallId, pending.getToolName(), "用户拒绝执行该操作"));
        }

        saveMessages(runId, messages);
        return executeLoop(runId, messages, references, onEvent);
    }

    private AgentExecutionResult executeLoop(
            Long runId, List<ChatMessage> messages, List<Reference> references, Consumer<AgentEvent> onEvent) {
        updateStatus(runId, "EXECUTING");

        for (int iteration = 0; iteration < DEFAULT_MAX_ITERATIONS; iteration++) {
            long startMs = System.currentTimeMillis();
            ChatCompletionResponse response;
            try {
                response = chatClient.complete(new ChatCompletionRequest(
                        "deepseek", null, messages, Map.of(), toolRegistry.toolDefs()));
            } catch (Exception e) {
                log.error("Agent LLM 调用失败: runId={}", runId, e);
                updateStatus(runId, "FAILED");
                onEvent.accept(new AgentEvent("error", e.getMessage()));
                return new AgentExecutionResult(runId, "FAILED", null, references, null);
            }
            recordLlmStep(runId, iteration, response, System.currentTimeMillis() - startMs);

            if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
                for (ToolCall toolCall : response.toolCalls()) {
                    if (toolRegistry.requiresApproval(toolCall.name())) {
                        ToolCallRecord pending = new ToolCallRecord();
                        pending.setRunId(runId);
                        pending.setToolName(toolCall.name());
                        pending.setInput(toolCall.arguments());
                        pending.setStatus("WAITING_APPROVAL");
                        toolCallMapper.insert(pending);

                        updateStatus(runId, "WAITING_APPROVAL");
                        saveMessages(runId, messages);
                        onEvent.accept(new AgentEvent("approval_required", Map.of(
                                "runId", runId,
                                "toolCallId", pending.getId(),
                                "toolName", toolCall.name(),
                                "arguments", toolCall.arguments())));
                        return new AgentExecutionResult(
                                runId, "WAITING_APPROVAL", null, references, pending.getId());
                    }

                    onEvent.accept(new AgentEvent("tool_start", Map.of(
                            "name", toolCall.name(), "arguments", toolCall.arguments())));
                    String output;
                    try {
                        output = executeTool(toolCall.name(), parseArguments(toolCall.arguments()));
                    } catch (Exception e) {
                        output = "工具执行失败: " + e.getMessage();
                    }
                    messages.add(ChatMessage.assistantWithToolCalls(List.of(toolCall)));
                    messages.add(ChatMessage.tool(toolCall.id(), toolCall.name(), output));
                }
            } else {
                String answer = response.content() == null ? "" : response.content();
                updateStatus(runId, "COMPLETED");
                onEvent.accept(new AgentEvent("done", Map.of("answer", answer, "references", references)));
                return new AgentExecutionResult(runId, "COMPLETED", answer, references, null);
            }
        }

        updateStatus(runId, "FAILED");
        String message = "达到最大迭代次数，仍未完成";
        onEvent.accept(new AgentEvent("error", message));
        return new AgentExecutionResult(runId, "FAILED", message, references, null);
    }

    private List<Reference> searchAndRecord(
            Long runId, Long kbId, String query, Consumer<AgentEvent> onEvent) {
        List<RetrievalResult> results = searchService.search(kbId, query, DEFAULT_TOP_K);
        List<Reference> references = results.stream()
                .map(result -> new Reference(
                        ((Number) result.metadata().getOrDefault("documentId", 0)).longValue(),
                        (Integer) result.metadata().getOrDefault("chunkIndex", 0),
                        result.content()))
                .toList();
        onEvent.accept(new AgentEvent("reference", references));

        AgentStep step = new AgentStep();
        step.setRunId(runId);
        step.setSeq(0);
        step.setStepType("RETRIEVE");
        step.setStatus("SUCCEEDED");
        step.setInput(toJson(query));
        step.setOutput(toJson(Map.of("count", references.size())));
        stepMapper.insert(step);
        return references;
    }

    private String executeTool(String toolName, Map<String, Object> arguments) {
        Tool tool = toolRegistry.get(toolName);
        if (tool == null) {
            throw new IllegalStateException("未知工具: " + toolName);
        }
        return tool.execute(arguments);
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具参数解析失败: " + argumentsJson, e);
        }
    }

    private String buildContext(String query, List<Reference> references) {
        StringBuilder sb = new StringBuilder("请基于以下知识库片段回答用户问题，并标注引用编号：\n\n");
        for (int i = 0; i < references.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(references.get(i).content()).append("\n");
        }
        sb.append("\n用户问题：").append(query);
        return sb.toString();
    }

    private void recordLlmStep(Long runId, int seq, ChatCompletionResponse response, long durationMs) {
        AgentStep step = new AgentStep();
        step.setRunId(runId);
        step.setSeq(seq + 1);
        step.setStepType("LLM");
        step.setStatus("SUCCEEDED");
        step.setOutput(toJson(Map.of(
                "finishReason", response.finishReason() == null ? "" : response.finishReason(),
                "toolCallCount", response.toolCalls() == null ? 0 : response.toolCalls().size())));
        step.setDurationMs((int) durationMs);
        stepMapper.insert(step);
    }

    private void saveMessages(Long runId, List<ChatMessage> messages) {
        redis.opsForValue().set("agent:messages:" + runId, toJson(messages), MESSAGE_TTL);
    }

    private List<ChatMessage> loadMessages(Long runId) {
        String json = redis.opsForValue().get("agent:messages:" + runId);
        if (json == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "会话上下文已过期，无法恢复审批");
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("会话上下文反序列化失败", e);
        }
    }

    private void saveReferences(Long runId, List<Reference> references) {
        redis.opsForValue().set("agent:references:" + runId, toJson(references), MESSAGE_TTL);
    }

    private List<Reference> loadReferences(Long runId) {
        String json = redis.opsForValue().get("agent:references:" + runId);
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("引用上下文反序列化失败", e);
        }
    }

    private void updateStatus(Long runId, String status) {
        runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, runId)
                .set(AgentRun::getStatus, status));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
