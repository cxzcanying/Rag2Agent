package com.rag2agent.bootstrap.agent;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.AgentProperties;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 受控多 Agent 研究流程：Retriever 完成后并行分派 Analyst，最后由 Reviewer 汇总。
 * 这是有边界的顺序拓扑，不执行自由群聊或无限递归委派。
 */
@Service
public class MultiAgentResearchService {

    private static final int TOP_K = 8;
    private static final int MODEL_REFERENCE_MAX_CHARS = 800;

    private final AgentRunMapper runMapper;
    private final AgentTaskService taskService;
    private final HybridSearchService searchService;
    private final ChatModelClient chatClient;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;
    private final Executor workerExecutor;
    private final ContextCompactor contextCompactor;

    public MultiAgentResearchService(
            AgentRunMapper runMapper,
            AgentTaskService taskService,
            HybridSearchService searchService,
            ChatModelClient chatClient,
            KnowledgeBaseService knowledgeBaseService,
            ObjectMapper objectMapper,
            AgentProperties agentProperties,
            @Qualifier("agentWorkerTaskExecutor") Executor workerExecutor) {
        this.runMapper = runMapper;
        this.taskService = taskService;
        this.searchService = searchService;
        this.chatClient = chatClient;
        this.knowledgeBaseService = knowledgeBaseService;
        this.objectMapper = objectMapper;
        this.agentProperties = agentProperties;
        this.workerExecutor = workerExecutor;
        this.contextCompactor = new ContextCompactor(agentProperties.getSummaryMaxChars());
    }

    public TeamResearchResponse research(Long userId, TeamResearchRequest request) {
        knowledgeBaseService.requireOwned(userId, request.kbId());
        int analystWorkers = agentProperties.getTeamAnalystWorkers();
        if (analystWorkers < 2 || analystWorkers > 4) {
            throw new IllegalArgumentException("teamAnalystWorkers 必须在 2~4 之间");
        }
        AgentRun run = new AgentRun();
        run.setUserId(userId);
        run.setSessionId("team-" + UUID.randomUUID());
        run.setQuery(request.query());
        run.setStatus("EXECUTING");
        run.setMaxIterations(3);
        runMapper.insert(run);

        long reviewerTask = 0;
        try {
            long retrieveTask = taskService.create(
                    run.getId(), null, "retriever", "RETRIEVE",
                    "检索证据", request.query(), List.of());
            startOrThrow(retrieveTask, "retriever");
            AgentWorkerBudget retrieverBudget = budget();
            Callable<List<Reference>> retrievalWorker = () -> {
                retrieverBudget.useTool();
                return toReferences(searchService.search(request.kbId(), request.query(), TOP_K));
            };
            WorkerOutcome<List<Reference>> retrieved = runWorkers(
                    List.of(retrievalWorker),
                    List.of(retrieverBudget)).getFirst();
            if (!retrieved.success()) {
                taskService.fail(retrieveTask, retrieved.error().getMessage());
                throw retrieved.error();
            }
            List<Reference> references = retrieved.value();
            taskService.complete(retrieveTask, Map.of(
                    "count", references.size(),
                    "evidence", evidenceText(references),
                    "budget", retrieverBudget.snapshot()));

            List<AnalystJob> analysts = createAnalystJobs(
                    run.getId(), retrieveTask, request.query(), references, analystWorkers);
            List<WorkerOutcome<AnalystDraft>> analystResults = runWorkers(
                    analysts.stream().map(AnalystJob::worker).toList(),
                    analysts.stream().map(AnalystJob::budget).toList());
            List<AnalystDraft> drafts = new ArrayList<>();
            List<String> workerFailures = new ArrayList<>();
            List<Long> completedAnalystTaskIds = new ArrayList<>();
            for (int index = 0; index < analysts.size(); index++) {
                AnalystJob job = analysts.get(index);
                WorkerOutcome<AnalystDraft> outcome = analystResults.get(index);
                if (outcome.success()) {
                    AnalystDraft draft = outcome.value();
                    int completed = taskService.complete(job.taskId(), Map.of(
                            "draft", draft.content(),
                            "focus", draft.focus(),
                            "budget", job.budget().snapshot()));
                    if (completed == 1) {
                        drafts.add(draft);
                        completedAnalystTaskIds.add(job.taskId());
                    } else {
                        workerFailures.add(job.assignee() + " 完成后任务状态更新失败");
                        taskService.fail(job.taskId(), "任务完成状态更新失败");
                    }
                } else {
                    workerFailures.add(job.assignee() + " 失败：" + outcome.error().getMessage());
                    taskService.fail(job.taskId(), outcome.error().getMessage());
                }
            }
            if (drafts.isEmpty()) {
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "全部分析 Worker 执行失败");
            }

            reviewerTask = taskService.create(
                    run.getId(), retrieveTask, "reviewer", "REVIEW",
                    "综合并复核多 Agent 结果", request.query(), completedAnalystTaskIds);
            startOrThrow(reviewerTask, "reviewer");
            AgentWorkerBudget reviewerBudget = budget();
            String combinedDrafts = analystDrafts(drafts);
            String review = complete(
                    """
                    你是复核 Agent。综合多个分析 Worker 的独立结果，检查答案是否被证据支持、
                    引用是否有效、是否遗漏关键限定或存在冲突。
                    只输出 JSON：{"approved":true|false,"answer":"最终答案"}。
                    若证据不足，approved=false，并在 answer 中说明缺口。
                    """,
                    "问题：" + request.query() + "\n证据：\n" + boundedEvidenceText(references)
                            + "\n分析 Worker 结果：\n" + combinedDrafts,
                    reviewerBudget);
            ReviewedAnswer reviewed = parseReviewedAnswer(review, drafts.getFirst().content());
            taskService.complete(reviewerTask, Map.of(
                    "review", review,
                    "approved", reviewed.approved(),
                    "workerFailures", workerFailures,
                    "budget", reviewerBudget.snapshot()));
            String status = workerFailures.isEmpty() && reviewed.approved()
                    ? "COMPLETED"
                    : "DEGRADED";
            finish(run.getId(), status, reviewed.answer());
            return new TeamResearchResponse(
                    run.getId(), status, reviewed.answer(), references);
        } catch (RuntimeException exception) {
            if (reviewerTask > 0) {
                taskService.fail(reviewerTask, exception.getMessage());
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

    private List<AnalystJob> createAnalystJobs(
            Long runId,
            long retrieveTask,
            String query,
            List<Reference> references,
            int count) {
        List<String> focuses = List.of(
                "提取可验证事实、指标、定义、时间和条件",
                "检查冲突、证据缺口、风险和适用边界",
                "补充关键实体、关系和多跳线索",
                "核对引用覆盖与遗漏信息");
        List<AnalystJob> jobs = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String focus = focuses.get(index);
            String assignee = "analyst-" + (index + 1);
            long taskId = taskService.create(
                    runId, retrieveTask, assignee, "ANALYZE",
                    focus, query, List.of(retrieveTask));
            AgentWorkerBudget budget = budget();
            jobs.add(new AnalystJob(
                    taskId,
                    assignee,
                    budget,
                    () -> {
                        startOrThrow(taskId, assignee);
                        String content = complete(
                                "你是独立分析 Agent。当前职责：" + focus
                                        + "。只基于给定证据工作，使用 [编号] 标注引用，不与其他 Worker 通信。",
                                "问题：" + query + "\n\n证据：\n" + boundedEvidenceText(references),
                                budget);
                        return new AnalystDraft(focus, content);
                    }));
        }
        return jobs;
    }

    private <T> List<WorkerOutcome<T>> runWorkers(
            List<Callable<T>> workers, List<AgentWorkerBudget> budgets) {
        if (workers.size() != budgets.size()) {
            throw new IllegalArgumentException("Worker 与预算数量不一致");
        }
        List<FutureTask<T>> futures = new ArrayList<>();
        List<Boolean> submitted = new ArrayList<>();
        for (int index = 0; index < workers.size(); index++) {
            Callable<T> worker = workers.get(index);
            AgentWorkerBudget budget = budgets.get(index);
            FutureTask<T> future = new FutureTask<>(() -> {
                budget.checkTime();
                T result = worker.call();
                budget.checkTime();
                return result;
            });
            futures.add(future);
            try {
                workerExecutor.execute(future);
                submitted.add(true);
            } catch (RejectedExecutionException exception) {
                submitted.add(false);
            }
        }

        List<WorkerOutcome<T>> outcomes = new ArrayList<>();
        for (int index = 0; index < futures.size(); index++) {
            FutureTask<T> future = futures.get(index);
            AgentWorkerBudget budget = budgets.get(index);
            if (!submitted.get(index)) {
                outcomes.add(WorkerOutcome.failure(new BusinessException(
                        ErrorCode.UPSTREAM_UNAVAILABLE, "Agent Worker 队列已满")));
                continue;
            }
            try {
                outcomes.add(WorkerOutcome.success(
                        future.get(budget.remainingMillis(), TimeUnit.MILLISECONDS)));
            } catch (TimeoutException exception) {
                future.cancel(true);
                outcomes.add(WorkerOutcome.failure(new BusinessException(
                        ErrorCode.RATE_LIMITED, "Agent Worker 时长预算已耗尽")));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                futures.forEach(pending -> pending.cancel(true));
                throw new IllegalStateException("多 Agent Worker 等待被中断", exception);
            } catch (ExecutionException exception) {
                outcomes.add(WorkerOutcome.failure(propagate(exception.getCause())));
            }
        }
        return outcomes;
    }

    private RuntimeException propagate(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException("Agent Worker 执行失败", cause);
    }

    private AgentWorkerBudget budget() {
        return new AgentWorkerBudget(agentProperties, contextCompactor);
    }

    private String complete(
            String systemPrompt, String userPrompt, AgentWorkerBudget budget) {
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", systemPrompt),
                new ChatMessage("user", userPrompt));
        int maxOutputTokens = budget.beginModel(messages);
        ChatCompletionResponse response = chatClient.complete(new ChatCompletionRequest(
                chatClient.providerName(),
                null,
                messages,
                modelOptions(maxOutputTokens, userPrompt)));
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new IllegalStateException("Agent 未返回有效答案");
        }
        budget.finishModel(response);
        return response.content().trim();
    }

    private Map<String, Object> modelOptions(int maxOutputTokens, String userPrompt) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("max_tokens", maxOutputTokens);
        options.put("temperature", 0.1);
        String reasoningEffort = ReasoningModeRouter.resolve(
                agentProperties.getReasoningEffort(), userPrompt);
        if (!"off".equals(reasoningEffort)) {
            options.put("reasoning_effort", reasoningEffort);
        }
        return Map.copyOf(options);
    }

    private ReviewedAnswer parseReviewedAnswer(String review, String fallback) {
        try {
            int start = review.indexOf('{');
            int end = review.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return new ReviewedAnswer(false, fallback);
            }
            JsonNode json = objectMapper.readTree(review.substring(start, end + 1));
            String answer = json.path("answer").asText("").trim();
            return new ReviewedAnswer(
                    json.path("approved").asBoolean(false),
                    answer.isBlank() ? fallback : answer);
        } catch (Exception exception) {
            return new ReviewedAnswer(false, fallback);
        }
    }

    private String analystDrafts(List<AnalystDraft> drafts) {
        StringBuilder combined = new StringBuilder();
        for (AnalystDraft draft : drafts) {
            combined.append("[").append(draft.focus()).append("]\n")
                    .append(draft.content()).append("\n\n");
        }
        return combined.toString().trim();
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

    private String boundedEvidenceText(List<Reference> references) {
        StringBuilder evidence = new StringBuilder();
        for (int index = 0; index < references.size(); index++) {
            String content = references.get(index).content();
            evidence.append("[").append(index + 1).append("] ");
            if (content.length() <= MODEL_REFERENCE_MAX_CHARS) {
                evidence.append(content);
            } else {
                evidence.append(content, 0, MODEL_REFERENCE_MAX_CHARS).append("…[已截断]");
            }
            evidence.append("\n");
        }
        return evidence.toString();
    }

    private void finish(Long runId, String status, String answer) {
        runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, runId)
                .set(AgentRun::getStatus, status)
                .set(AgentRun::getAnswer, answer));
    }

    private record AnalystJob(
            long taskId, String assignee, AgentWorkerBudget budget, Callable<AnalystDraft> worker) {}

    private record AnalystDraft(String focus, String content) {}

    private record ReviewedAnswer(boolean approved, String answer) {}

    private record WorkerOutcome<T>(T value, RuntimeException error) {

        static <T> WorkerOutcome<T> success(T value) {
            return new WorkerOutcome<>(value, null);
        }

        static <T> WorkerOutcome<T> failure(RuntimeException error) {
            return new WorkerOutcome<>(null, error);
        }

        boolean success() {
            return error == null;
        }
    }
}
