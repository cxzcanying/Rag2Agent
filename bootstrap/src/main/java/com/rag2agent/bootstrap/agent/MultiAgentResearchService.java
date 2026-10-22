package com.rag2agent.bootstrap.agent;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.dto.AgentDtos.TeamResearchRequest;
import com.rag2agent.bootstrap.dto.AgentDtos.TeamResearchResponse;
import com.rag2agent.bootstrap.entity.AgentRun;
import com.rag2agent.bootstrap.mapper.AgentRunMapper;
import com.rag2agent.bootstrap.service.HybridSearchService;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import com.rag2agent.infra.ai.client.ChatModelClient;
import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import com.rag2agent.infra.ai.model.ChatMessage;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 受控多 Agent 研究流程：Coordinator 创建任务，Retriever、Analyst、Reviewer 使用独立上下文。
 * 这是有边界的顺序拓扑，不执行自由群聊或无限递归委派。
 */
@Service
public class MultiAgentResearchService {

    private static final int TOP_K = 8;

    private final AgentRunMapper runMapper;
    private final AgentTaskService taskService;
    private final HybridSearchService searchService;
    private final ChatModelClient chatClient;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;

    public MultiAgentResearchService(
            AgentRunMapper runMapper,
            AgentTaskService taskService,
            HybridSearchService searchService,
            ChatModelClient chatClient,
            KnowledgeBaseService knowledgeBaseService,
            ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.taskService = taskService;
        this.searchService = searchService;
        this.chatClient = chatClient;
        this.knowledgeBaseService = knowledgeBaseService;
        this.objectMapper = objectMapper;
    }

    public TeamResearchResponse research(Long userId, TeamResearchRequest request) {
        knowledgeBaseService.requireOwned(userId, request.kbId());
        AgentRun run = new AgentRun();
        run.setUserId(userId);
        run.setSessionId("team-" + UUID.randomUUID());
        run.setQuery(request.query());
        run.setStatus("EXECUTING");
        run.setMaxIterations(3);
        runMapper.insert(run);

        long currentTask = 0;
        try {
            long retrieveTask = taskService.create(
                    run.getId(), null, "retriever", "RETRIEVE",
                    "检索证据", request.query(), List.of());
            currentTask = retrieveTask;
            startOrThrow(retrieveTask, "retriever");
            List<RetrievalResult> results = searchService.search(request.kbId(), request.query(), TOP_K);
            List<Reference> references = toReferences(results);
            taskService.complete(retrieveTask, Map.of(
                    "count", results.size(),
                    "evidence", evidenceText(references)));

            long analyzeTask = taskService.create(
                    run.getId(), retrieveTask, "analyst", "ANALYZE",
                    "基于证据形成答案", request.query(), List.of(retrieveTask));
            currentTask = analyzeTask;
            startOrThrow(analyzeTask, "analyst");
            String draft = complete(
                    "你是分析 Agent。只基于给定证据回答，使用 [编号] 标注引用；证据不足时明确说明。",
                    "问题：" + request.query() + "\n\n证据：\n" + evidenceText(references));
            taskService.complete(analyzeTask, Map.of("draft", draft));

            long reviewTask = taskService.create(
                    run.getId(), analyzeTask, "reviewer", "REVIEW",
                    "独立复核答案", request.query(), List.of(analyzeTask));
            currentTask = reviewTask;
            startOrThrow(reviewTask, "reviewer");
            String review = complete(
                    """
                    你是复核 Agent。检查答案是否被证据支持、引用是否有效、是否遗漏关键限定。
                    只输出 JSON：{"approved":true|false,"answer":"最终答案"}。
                    若证据不足，approved=false，并在 answer 中说明缺口。
                    """,
                    "问题：" + request.query() + "\n证据：\n" + evidenceText(references)
                            + "\n待复核答案：\n" + draft);
            String finalAnswer = parseReviewedAnswer(review, draft);
            taskService.complete(reviewTask, Map.of("review", review));
            finish(run.getId(), "COMPLETED", finalAnswer);
            return new TeamResearchResponse(
                    run.getId(), "COMPLETED", finalAnswer, references);
        } catch (RuntimeException exception) {
            if (currentTask > 0) {
                taskService.fail(currentTask, exception.getMessage());
            }
            finish(run.getId(), "FAILED", null);
            throw exception;
        }
    }

    private void startOrThrow(long taskId, String assignee) {
        if (taskService.start(taskId, assignee) != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent 任务状态已变化");
        }
    }

    private String complete(String systemPrompt, String userPrompt) {
        ChatCompletionResponse response = chatClient.complete(new ChatCompletionRequest(
                chatClient.providerName(),
                null,
                List.of(new ChatMessage("system", systemPrompt), new ChatMessage("user", userPrompt)),
                Map.of("max_tokens", 1200, "temperature", 0.1)));
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new IllegalStateException("Agent 未返回有效答案");
        }
        return response.content().trim();
    }

    private String parseReviewedAnswer(String review, String fallback) {
        try {
            int start = review.indexOf('{');
            int end = review.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return fallback;
            }
            JsonNode json = objectMapper.readTree(review.substring(start, end + 1));
            String answer = json.path("answer").asText("").trim();
            return answer.isBlank() ? fallback : answer;
        } catch (Exception exception) {
            return fallback;
        }
    }

    private List<Reference> toReferences(List<RetrievalResult> results) {
        return results.stream().map(result -> new Reference(
                ((Number) result.metadata().getOrDefault("documentId", 0)).longValue(),
                ((Number) result.metadata().getOrDefault("chunkIndex", 0)).intValue(),
                result.metadata().get("expandedContent") instanceof String expanded
                        ? expanded
                        : result.content(),
                result.metadata())).toList();
    }

    private String evidenceText(List<Reference> references) {
        StringBuilder evidence = new StringBuilder();
        for (int index = 0; index < references.size(); index++) {
            evidence.append("[").append(index + 1).append("] ")
                    .append(references.get(index).content()).append("\n");
        }
        return evidence.toString();
    }

    private void finish(Long runId, String status, String answer) {
        runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, runId)
                .set(AgentRun::getStatus, status)
                .set(AgentRun::getAnswer, answer));
    }
}
