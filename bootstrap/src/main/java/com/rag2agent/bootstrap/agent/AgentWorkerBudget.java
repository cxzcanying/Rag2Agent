package com.rag2agent.bootstrap.agent;

import com.rag2agent.bootstrap.config.AgentProperties;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import com.rag2agent.infra.ai.model.ChatMessage;
import com.rag2agent.infra.ai.model.ToolDef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 单个 Agent Worker 的硬预算；在发起模型和工具调用前检查，结束后记录实际消耗。 */
final class AgentWorkerBudget {

    private final int tokenLimit;
    private final int toolLimit;
    private final int maxOutputTokens;
    private final long startedNanos = System.nanoTime();
    private final long timeoutMillis;
    private final ContextCompactor compactor;
    private int tokensUsed;
    private int pendingPromptTokens;
    private int toolCalls;

    AgentWorkerBudget(AgentProperties properties, ContextCompactor compactor) {
        if (properties.getTeamWorkerTokenBudget() < 1
                || properties.getTeamWorkerToolBudget() < 0
                || properties.getTeamWorkerTimeoutMillis() < 1) {
            throw new IllegalArgumentException("多 Agent Worker 预算配置无效");
        }
        this.tokenLimit = properties.getTeamWorkerTokenBudget();
        this.toolLimit = properties.getTeamWorkerToolBudget();
        this.maxOutputTokens = properties.getMaxOutputTokens();
        this.timeoutMillis = properties.getTeamWorkerTimeoutMillis();
        this.compactor = compactor;
    }

    int beginModel(List<ChatMessage> messages) {
        return beginModel(messages, List.of());
    }

    int beginModel(List<ChatMessage> messages, List<ToolDef> tools) {
        checkTime();
        int promptTokens = compactor.estimateTokens(messages)
                + compactor.estimateText(String.valueOf(tools));
        int available = tokenLimit - tokensUsed;
        if (promptTokens >= available) {
            throw exceeded("Token");
        }
        tokensUsed += promptTokens;
        pendingPromptTokens = promptTokens;
        return Math.min(maxOutputTokens, available - promptTokens);
    }

    void finishModel(ChatCompletionResponse response) {
        checkTime();
        Map<String, Object> usage = response.usage();
        long actualPrompt = number(usage, "prompt_tokens");
        if (actualPrompt > pendingPromptTokens) {
            tokensUsed += Math.toIntExact(actualPrompt - pendingPromptTokens);
        }
        long actualCompletion = number(usage, "completion_tokens");
        if (actualCompletion <= 0) {
            actualCompletion = compactor.estimateTokens(
                    List.of(new ChatMessage("assistant", response.content())));
        }
        tokensUsed += Math.toIntExact(actualCompletion);
        pendingPromptTokens = 0;
        if (tokensUsed > tokenLimit) {
            throw exceeded("Token");
        }
    }

    void useTool() {
        checkTime();
        toolCalls++;
        if (toolCalls > toolLimit) {
            throw exceeded("工具");
        }
    }

    void checkTime() {
        if (elapsedMillis() > timeoutMillis) {
            throw exceeded("时长");
        }
    }

    long timeoutMillis() {
        return timeoutMillis;
    }

    long remainingMillis() {
        return Math.max(0, timeoutMillis - elapsedMillis());
    }

    Map<String, Object> snapshot() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("tokenLimit", tokenLimit);
        usage.put("tokensUsed", tokensUsed);
        usage.put("toolLimit", toolLimit);
        usage.put("toolCalls", toolCalls);
        usage.put("timeoutMillis", timeoutMillis);
        usage.put("durationMillis", elapsedMillis());
        return Map.copyOf(usage);
    }

    private long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private BusinessException exceeded(String resource) {
        return new BusinessException(
                ErrorCode.RATE_LIMITED, "Agent Worker " + resource + "预算已耗尽");
    }

    private static long number(Map<String, Object> usage, String key) {
        if (usage == null) {
            return 0;
        }
        return usage.get(key) instanceof Number value ? value.longValue() : 0;
    }
}
