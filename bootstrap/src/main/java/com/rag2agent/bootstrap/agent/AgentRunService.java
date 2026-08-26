package com.rag2agent.bootstrap.agent;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.entity.AgentRun;
import com.rag2agent.bootstrap.entity.AgentStep;
import com.rag2agent.bootstrap.entity.ToolCallRecord;
import com.rag2agent.bootstrap.config.AgentProperties;
import com.rag2agent.bootstrap.mapper.AgentRunMapper;
import com.rag2agent.bootstrap.mapper.AgentStepMapper;
import com.rag2agent.bootstrap.mapper.ToolCallRecordMapper;
import com.rag2agent.bootstrap.mapper.DocumentMetaMapper;
import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.bootstrap.service.HybridSearchService;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import com.rag2agent.bootstrap.tool.Tool;
import com.rag2agent.bootstrap.tool.ToolRegistry;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import com.rag2agent.infra.ai.client.ChatModelClient;
import com.rag2agent.infra.ai.exception.AiClientException;
import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import com.rag2agent.infra.ai.model.ChatMessage;
import com.rag2agent.infra.ai.model.ToolCall;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Agent 执行状态机：INIT -> ROUTING -> EXECUTING -> WAITING_APPROVAL -> FINALIZING -> COMPLETED / FAILED。
 *
 * 编排：先主动检索（无引用不答）→ function calling 循环（模型决定调工具）→ 高风险工具挂起等审批。
 * 审批挂起时把消息历史存 Redis，审批通过后恢复并继续循环。
 * @author 21311
 */
@Service
public class AgentRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);
    private static final Duration MESSAGE_TTL = Duration.ofMinutes(30);
    private static final int DEFAULT_TOP_K = 5;
    private static final int DEFAULT_MAX_ITERATIONS = 10;
    private static final Duration SESSION_LOCK_TTL = Duration.ofMinutes(30);
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end";

    private static final String SYSTEM_PROMPT = """
            你是一个企业知识库问答助手。回答必须基于检索到的知识库内容，并标注引用编号（如 [1]）。
            如果检索结果不足以回答，如实说明"未找到相关资料"，不要编造。
            当用户要求删除文档时，即使检索结果为空也要调用 delete_document 工具；该操作需要人工审批。
            """;

    private final ChatModelClient chatClient;
    private final HybridSearchService searchService;
    private final ToolRegistry toolRegistry;
    private final AgentRunMapper runMapper;
    private final AgentStepMapper stepMapper;
    private final ToolCallRecordMapper toolCallMapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;
    private final AgentProperties agentProperties;
    private final ContextCompactor contextCompactor;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentMetaMapper documentMapper;

    public AgentRunService(
            ChatModelClient chatClient,
            HybridSearchService searchService,
            ToolRegistry toolRegistry,
            AgentRunMapper runMapper,
            AgentStepMapper stepMapper,
            ToolCallRecordMapper toolCallMapper,
            ObjectMapper objectMapper,
            StringRedisTemplate redis,
            MeterRegistry meterRegistry,
            ObservationRegistry observationRegistry,
            AgentProperties agentProperties,
            KnowledgeBaseService knowledgeBaseService,
            DocumentMetaMapper documentMapper) {
        this.chatClient = chatClient;
        this.searchService = searchService;
        this.toolRegistry = toolRegistry;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.toolCallMapper = toolCallMapper;
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry;
        this.agentProperties = agentProperties;
        this.contextCompactor = new ContextCompactor(agentProperties.getSummaryMaxChars());
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentMapper = documentMapper;
    }

    /**
     * 使用 Redis session 锁，防止同一会话同时执行多个请求。
     * @param userId
     * @param sessionId
     * @param query
     * @param kbId
     * @param onEvent
     * @return
     */
    public AgentExecutionResult start(
            Long userId, String sessionId, String query, Long kbId, Consumer<AgentEvent> onEvent) {
        if (userId == null || userId <= 0 || sessionId == null
                || sessionId.isBlank() || sessionId.length() > 64) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户或 sessionId 无效");
        }
        String lockKey = "rag2agent:agent:session-lock:" + userId + ":" + sessionId;
        String lockToken = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, lockToken, SESSION_LOCK_TTL))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该会话已有请求正在执行，请稍后重试");
        }
        try {
            return startLocked(userId, sessionId, query, kbId, onEvent);
        } finally {
            redis.execute(new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class), List.of(lockKey), lockToken);
        }
    }

    /**
     * 集中校验用户、session、输入长度和知识库归属。
     * @param userId
     * @param sessionId
     * @param query
     * @param kbId
     * @param onEvent
     * @return
     */
    private AgentExecutionResult startLocked(
            Long userId, String sessionId, String query, Long kbId, Consumer<AgentEvent> onEvent) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }
        if (kbId == null || kbId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "kbId 必须为正数");
        }
        knowledgeBaseService.requireOwned(userId, kbId);
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "query 不能为空");
        }
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 64) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "sessionId 无效");
        }
        if (query.length() > agentProperties.getMaxInputChars()) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "输入过长，最多允许 " + agentProperties.getMaxInputChars() + " 个字符");
        }
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
            // 检索为空时不直接拒绝：把"未检索到内容"交给模型，让它决定是调用工具（如删除文档）还是如实回答未找到。
            // 直接拦截会导致"删除文档 X"这类操作请求永远进不了工具循环。
            messages.add(new ChatMessage("user",
                    "知识库检索结果为空。如果用户要求的是删除/修改等工具操作，请直接调用对应工具；"
                            + "如果是提问，请如实回答\"未找到相关资料\"，不要编造。"));
        } else {
            messages.add(new ChatMessage("user", buildContext(query, references)));
        }
        saveMessages(run.getId(), messages);
        saveReferences(run.getId(), references);

        return executeLoop(run.getId(), run.getUserId(), messages, references, run.getMaxIterations(), onEvent);
    }

    /**
     * 校验 run 所属用户，并通过数据库 CAS 防止重复审批
     * @param runId
     * @param approved
     * @param onEvent
     * @return
     */
    public AgentExecutionResult approve(Long runId, boolean approved, Consumer<AgentEvent> onEvent) {
        if (runId == null || runId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "runId 必须为正数");
        }
        AgentRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent 执行不存在");
        }
        Long currentUserId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        if (!currentUserId.equals(run.getUserId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent 执行不存在");
        }
        // CAS 抢占审批：把 WAITING_APPROVAL 置为 EXECUTING，影响行数为 0 说明已被并发审批处理，避免重复执行工具
        int claimed = runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, runId)
                .eq(AgentRun::getStatus, "WAITING_APPROVAL")
                .set(AgentRun::getStatus, "EXECUTING"));
        if (claimed == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该审批已被处理或执行状态已变化");
        }
        run.setStatus("EXECUTING");
        ToolCallRecord pending = toolCallMapper.listByRunId(runId).stream()
                .filter(call -> "WAITING_APPROVAL".equals(call.getStatus()))
                .findFirst()
                .orElseGet(() -> null);
        if (pending == null || toolCallMapper.claimApproval(pending.getId(), runId) != 1) {
            updateStatus(runId, "FAILED");
            throw new BusinessException(ErrorCode.BAD_REQUEST, "待审批工具调用已被处理或不存在");
        }

        List<ChatMessage> messages = loadMessages(runId);
        List<Reference> references = loadReferences(runId);
        String toolCallId = String.valueOf(pending.getId());

        if (approved) {
            String output;
            try {
                output = executeTool(run.getUserId(), pending.getToolName(), parseArguments(pending.getInput()));
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
        return executeLoop(runId, run.getUserId(), messages, references, run.getMaxIterations(), onEvent);
    }

    /**
     * executeLoop() 每轮 LLM 调用前检查上下文 token 估算值。
     * 超出预算时发送：
     * - context_compaction.started
     * - context_compaction.completed
     *
     * 压缩后重新保存消息，并记录一个 CONTEXT_COMPACTION Agent 步骤。
     * LLM 请求增加 max_tokens。
     * @param runId
     * @param userId
     * @param messages
     * @param references
     * @param maxIterations
     * @param onEvent
     * @return
     */
    private AgentExecutionResult executeLoop(
            Long runId, Long userId, List<ChatMessage> messages, List<Reference> references,
            int maxIterations, Consumer<AgentEvent> onEvent) {
        updateStatus(runId, "EXECUTING");

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            int estimatedTokens = contextCompactor.estimateTokens(messages);
            if (estimatedTokens > agentProperties.getContextTokenBudget()) {
                onEvent.accept(new AgentEvent("context_compaction", Map.of(
                        "status", "started",
                        "estimatedTokens", estimatedTokens,
                        "budget", agentProperties.getContextTokenBudget())));
            }
            ContextCompactor.CompactionResult compaction = contextCompactor.compact(
                    messages, agentProperties.getContextTokenBudget());
            if (compaction.compacted()) {
                messages = new ArrayList<>(compaction.messages());
                saveMessages(runId, messages);
                recordCompaction(runId, iteration, compaction);
                onEvent.accept(new AgentEvent("context_compaction", Map.of(
                        "status", "completed",
                        "estimatedTokensBefore", compaction.estimatedTokensBefore(),
                        "estimatedTokensAfter", compaction.estimatedTokensAfter(),
                        "droppedMessages", compaction.droppedMessages(),
                        "budget", agentProperties.getContextTokenBudget())));
            }
            long startMs = System.currentTimeMillis();
            List<ChatMessage> requestMessages = messages;
            ChatCompletionResponse response;
            try {
                response = Observation.createNotStarted("rag2agent.agent.llm", observationRegistry)
                        .highCardinalityKeyValue("run.id", String.valueOf(runId))
                        .lowCardinalityKeyValue("operation", "chat")
                        .lowCardinalityKeyValue("provider", "deepseek")
                        .lowCardinalityKeyValue("model", "configured")
                        .observe(() -> chatClient.complete(new ChatCompletionRequest(
                                "deepseek", null, requestMessages,
                                Map.of("max_tokens", agentProperties.getMaxOutputTokens()),
                                toolRegistry.toolDefs())));
            } catch (Exception e) {
                recordLlmMetric(startMs, "error");
                log.error("Agent LLM 调用失败: runId={}", runId, e);
                if (e instanceof AiClientException && !references.isEmpty()) {
                    String answer = "模型服务暂不可用，以下是本次检索到的参考资料。请稍后重试生成答案。";
                    updateStatus(runId, "DEGRADED");
                    recordAgentTransition("degraded");
                    onEvent.accept(new AgentEvent("done", Map.of(
                            "answer", answer, "references", references, "degraded", true)));
                    return new AgentExecutionResult(runId, "DEGRADED", answer, references, null);
                }
                updateStatus(runId, "FAILED");
                recordAgentTransition("failed");
                onEvent.accept(new AgentEvent("error", safeAiError(e)));
                return new AgentExecutionResult(runId, "FAILED", null, references, null);
            }
            recordLlmMetric(startMs, "success");
            if (response != null) {
                recordTokens(response.usage());
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
                        recordAgentTransition("waiting_approval");
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
                        output = executeTool(userId, toolCall.name(), parseArguments(toolCall.arguments()));
                    } catch (Exception e) {
                        output = "工具执行失败: " + e.getMessage();
                    }
                    messages.add(ChatMessage.assistantWithToolCalls(List.of(toolCall)));
                    messages.add(ChatMessage.tool(toolCall.id(), toolCall.name(), output));
                }
            } else {
                String answer = response.content() == null ? "" : response.content();
                updateStatus(runId, "COMPLETED");
                recordAgentTransition("completed");
                onEvent.accept(new AgentEvent("done", Map.of("answer", answer, "references", references)));
                return new AgentExecutionResult(runId, "COMPLETED", answer, references, null);
            }
        }

        return finalizeAtMaxSteps(runId, messages, references, maxIterations, onEvent);
    }

    AgentExecutionResult finalizeAtMaxSteps(
            Long runId,
            List<ChatMessage> messages,
            List<Reference> references,
            int maxIterations,
            Consumer<AgentEvent> onEvent) {
        updateStatus(runId, "FINALIZING");
        List<ChatMessage> finalMessages = new ArrayList<>(messages);
        finalMessages.add(new ChatMessage(
                "user",
                "工具调用次数已达上限。禁止再调用任何工具，请基于已有上下文和工具结果直接给出最终回答；"
                        + "资料不足时明确说明，不要编造。"));
        long startMs = System.currentTimeMillis();
        try {
            ChatCompletionResponse response = Observation.createNotStarted(
                            "rag2agent.agent.llm", observationRegistry)
                    .highCardinalityKeyValue("run.id", String.valueOf(runId))
                    .lowCardinalityKeyValue("operation", "finalize")
                    .lowCardinalityKeyValue("provider", "deepseek")
                    .lowCardinalityKeyValue("model", "configured")
                    .observe(() -> chatClient.complete(new ChatCompletionRequest(
                            "deepseek",
                            null,
                            finalMessages,
                            Map.of("max_tokens", agentProperties.getMaxOutputTokens()))));
            if (response == null || response.content() == null || response.content().isBlank()) {
                throw new IllegalStateException("最大步数总结未返回有效答案");
            }
            recordLlmMetric(startMs, "success");
            recordTokens(response.usage());
            recordLlmStep(runId, maxIterations + 1, response, System.currentTimeMillis() - startMs);
            String answer = response.content().trim();
            updateStatus(runId, "MAX_STEPS_REACHED");
            recordAgentTransition("max_steps_reached");
            onEvent.accept(new AgentEvent("done", Map.of(
                    "answer", answer,
                    "references", references,
                    "maxStepsReached", true)));
            return new AgentExecutionResult(runId, "MAX_STEPS_REACHED", answer, references, null);
        } catch (Exception exception) {
            recordLlmMetric(startMs, "error");
            log.error("Agent 最大步数总结失败: runId={}", runId, exception);
            updateStatus(runId, "FAILED");
            recordAgentTransition("failed");
            String message = safeAiError(exception);
            onEvent.accept(new AgentEvent("error", message));
            return new AgentExecutionResult(runId, "FAILED", null, references, null);
        }
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

    private String executeTool(Long userId, String toolName, Map<String, Object> arguments) {
        validateToolAccess(userId, toolName, arguments);
        Tool tool = toolRegistry.get(toolName);
        if (tool == null) {
            throw new IllegalStateException("未知工具: " + toolName);
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            return Observation.createNotStarted("rag2agent.agent.tool", observationRegistry)
                    .lowCardinalityKeyValue("tool", toolName)
                    .observe(() -> tool.execute(arguments));
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(Timer.builder("rag2agent.agent.tool.duration")
                    .tag("tool", toolName)
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    private void validateToolAccess(Long userId, String toolName, Map<String, Object> arguments) {
        if ("search_knowledge_base".equals(toolName)) {
            Object value = arguments.get("kb_id");
            if (!(value instanceof Number number)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "工具参数 kb_id 无效");
            }
            knowledgeBaseService.requireOwned(userId, number.longValue());
            return;
        }
        if ("delete_document".equals(toolName)) {
            Object value = arguments.get("document_id");
            if (!(value instanceof Number number)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "工具参数 document_id 无效");
            }
            DocumentMeta document = documentMapper.selectById(number.longValue());
            if (document == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
            }
            knowledgeBaseService.requireOwned(userId, document.getKbId());
        }
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

    private void recordLlmMetric(long startedMs, String outcome) {
        Timer.builder("rag2agent.ai.chat.duration")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Duration.ofMillis(System.currentTimeMillis() - startedMs));
    }

    private void recordTokens(Map<String, Object> usage) {
        if (usage == null) {
            return;
        }
        recordTokenType(usage, "prompt_tokens", "prompt");
        recordTokenType(usage, "completion_tokens", "completion");
    }

    private void recordCompaction(Long runId, int iteration, ContextCompactor.CompactionResult result) {
        Counter.builder("rag2agent.agent.context.compactions")
                .register(meterRegistry)
                .increment();
        DistributionSummary.builder("rag2agent.agent.context.tokens.before")
                .register(meterRegistry)
                .record(result.estimatedTokensBefore());
        DistributionSummary.builder("rag2agent.agent.context.tokens.after")
                .register(meterRegistry)
                .record(result.estimatedTokensAfter());
        AgentStep step = new AgentStep();
        step.setRunId(runId);
        step.setSeq(1000 + iteration);
        step.setStepType("CONTEXT_COMPACTION");
        step.setStatus("SUCCEEDED");
        step.setOutput(toJson(Map.of(
                "estimatedTokensBefore", result.estimatedTokensBefore(),
                "estimatedTokensAfter", result.estimatedTokensAfter(),
                "droppedMessages", result.droppedMessages())));
        stepMapper.insert(step);
    }

    private void recordTokenType(Map<String, Object> usage, String key, String type) {
        Object value = usage.get(key);
        if (value instanceof Number number) {
            Counter.builder("rag2agent.ai.tokens")
                    .tag("type", type)
                    .register(meterRegistry)
                    .increment(number.doubleValue());
        }
    }

    private void recordAgentTransition(String status) {
        Counter.builder("rag2agent.agent.transitions")
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    private String safeAiError(Exception exception) {
        if (exception instanceof AiClientException aiException) {
            return switch (aiException.kind()) {
                case TIMEOUT -> "模型服务响应超时，请稍后重试";
                case RATE_LIMITED -> "模型服务限流，请稍后重试";
                case UPSTREAM_ERROR -> "模型服务暂时不可用，请稍后重试";
                case CIRCUIT_OPEN -> "模型服务熔断中，请稍后重试";
                case BULKHEAD_REJECTED -> "模型并发已达上限，请稍后重试";
                case CLIENT_ERROR -> "模型请求参数无效";
            };
        }
        return "Agent 执行失败，请稍后重试";
    }
}
