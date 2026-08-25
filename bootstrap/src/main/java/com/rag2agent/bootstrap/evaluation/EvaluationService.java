package com.rag2agent.bootstrap.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.dto.EvaluationDtos.CaseInput;
import com.rag2agent.bootstrap.dto.EvaluationDtos.CaseResult;
import com.rag2agent.bootstrap.dto.EvaluationDtos.EvaluationConfig;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunStatus;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunSubmission;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunSummary;
import com.rag2agent.bootstrap.service.SearchOptions;
import com.rag2agent.bootstrap.service.HybridSearchService;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import com.rag2agent.infra.ai.client.ChatModelClient;
import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import com.rag2agent.infra.ai.model.ChatMessage;
import com.rag2agent.rag.core.evaluation.RetrievalMetrics;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import jakarta.annotation.PostConstruct;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评测服务，负责 RAG 系统的自动化评测
 * <p>
 * 核心流程：
 * 1. 导入测试用例（问题 + 参考答案 + 相关文档ID）
 * 2. 提交评测任务（指定知识库、评测配置）
 * 3. 异步执行评测：检索 -> 生成答案 -> 裁判模型打分
 * 4. 汇总指标（检索指标 + 生成质量指标）
 *
 * @author 21311
 */

@Service
public class EvaluationService {

    /**
     * 生成模型的系统提示词
     * 要求模型仅依据检索到的资料回答，资料不足时明确说不知道
     */
    private static final String GENERATION_SYSTEM = """
            你是企业知识库评测中的回答模型。只能依据给定资料回答，资料不足时明确说不知道。
            不要输出引用之外的事实。
            """;
    /**
     * 裁判模型的系统提示词
     * 要求输出严格的 JSON 格式，包含 faithfulness 和 answerCorrectness 两个指标
     */
    private static final String JUDGE_SYSTEM = """
            你是严格的 RAG 评测裁判。只输出 JSON，不要 Markdown：
            {"faithfulness":0到1之间的数字,"answerCorrectness":0到1之间的数字}
            faithfulness 衡量答案中的事实是否都能被资料支持；answerCorrectness 衡量答案是否回答了问题并符合参考答案。
            """;

    private final EvaluationRepository repository;
    private final HybridSearchService searchService;
    private final ChatModelClient chatClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Executor evaluationTaskExecutor;

    public EvaluationService(
            EvaluationRepository repository,
            HybridSearchService searchService,
            ChatModelClient chatClient,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @org.springframework.beans.factory.annotation.Qualifier("evaluationTaskExecutor") Executor evaluationTaskExecutor) {
        this.repository = repository;
        this.searchService = searchService;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.evaluationTaskExecutor = evaluationTaskExecutor;
    }

    /**
     * 批量导入测试用例
     *
     * @param kbId   知识库ID
     * @param cases  测试用例列表，每个用例包含：问题、期望答案、相关文档ID列表
     * @return 成功导入的用例数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int importCases(Long kbId, List<CaseInput> cases) {
        return repository.insertCases(kbId, cases);
    }

    /**
     * 应用重启后自动恢复未完成的评测任务
     * <p>
     * 使用 @PostConstruct 在 Bean 初始化完成后执行：
     * 1. 将状态为 RUNNING 的任务重新置为 QUEUED（因为进程已中断，这些任务实际上未完成）
     * 2. 重新调度所有 QUEUED 状态的任务
     */
    @PostConstruct
    void resumeQueuedRuns() {
        repository.requeueInterruptedRuns();
        repository.listQueuedRunIds().forEach(runId -> evaluationTaskExecutor.execute(() -> executeRun(runId)));
    }

    /**
     * 提交单个评测任务（核心方法）
     *
     * 流程：
     * 1. 规范化配置（补全默认值）
     * 2. 校验知识库是否有测试用例
     * 3. 处理幂等性（Idempotency-Key）
     * 4. 创建评测任务记录
     * 5. 异步执行评测
     *
     * @param kbId             知识库ID
     * @param name             任务名称
     * @param config           评测配置（检索参数、是否生成答案等）
     * @param idempotencyKey   幂等键（客户端生成的 UUID）
     * @return 任务提交结果，包含 runId 和状态信息
     */
    public RunSubmission submit(Long kbId, String name, EvaluationConfig config, String idempotencyKey) {
        // 规范化配置：补全未指定的参数为默认值
        EvaluationConfig effectiveConfig = config.normalized();
        // 获取该知识库的所有测试用例
        List<EvaluationRepository.EvalCase> cases = repository.listCases(kbId);
        if (cases.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该知识库没有评测用例，请先导入 cases");
        }
        // 幂等性处理
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        // 生成请求指纹：基于 kbId、name、config、所有 case 的 ID
        String fingerprint = fingerprint(kbId, name, effectiveConfig, cases);

        // 创建评测任务记录，返回创建结果（包含是否为新创建、runId、指纹等）
        EvaluationRepository.CreatedRun created = repository.createRun(
                kbId,
                name,
                toJson(effectiveConfig),
                cases.stream().map(EvaluationRepository.EvalCase::id).toList(),
                normalizedKey,
                fingerprint);

        // 幂等性校验：如果已存在Idempotency-Key相同的任务，但指纹不匹配，说明是不同请求使用了相同的 Idempotency-Key
        if (!fingerprint.equals(created.requestFingerprint())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Idempotency-Key 已用于不同的评测请求");
        }

        // 如果任务是新创建的，异步执行评测
        if (created.created()) {
            evaluationTaskExecutor.execute(() -> executeRun(created.runId()));
        }

        // 查询任务详情并返回
        EvaluationRepository.EvalRun run = repository.findRun(created.runId()).orElseThrow();
        return new RunSubmission(
                created.runId(),
                run.status(),
                run.totalCases(),
                run.completedCases(),
                // 如果是幂等返回，标记为重复提交
                !created.created());
    }

    /**
     * 提交矩阵评测任务（批量）
     *
     * 用于对比实验：同一个知识库，用多组不同的配置分别运行评测
     * 例如：一次性测试 5 种不同的检索参数组合
     *
     * @param kbId         知识库ID
     * @param namePrefix   任务名称前缀（实际名称为 prefix-1, prefix-2, ...）
     * @param configs      多组评测配置
     * @param idempotencyKey  幂等键（会为每个子任务追加序号）
     * @return 所有子任务的提交结果列表
     */
    public List<RunSubmission> submitMatrix(
            Long kbId, String namePrefix, List<EvaluationConfig> configs, String idempotencyKey) {
        List<RunSubmission> submissions = new ArrayList<>();
        for (int i = 0; i < configs.size(); i++) {
            // 如果提供了幂等键，每个子任务使用 "原key-序号" 作为幂等键
            String key = idempotencyKey == null ? null : idempotencyKey + "-" + (i + 1);
            submissions.add(submit(kbId, namePrefix + "-" + (i + 1), configs.get(i), key));
        }
        return submissions;
    }

    /**
     * 执行单个评测任务（异步执行，在独立线程中运行）
     *
     * 流程：
     * 1. 将任务状态从 QUEUED 更新为 RUNNING（使用 CAS 防止重复执行）
     * 2. 遍历所有测试用例，逐个执行评测
     * 3. 对每个用例：检索 -> 生成答案 -> 裁判打分
     * 4. 汇总所有用例的结果，计算整体指标
     * 5. 更新任务状态为 COMPLETED 或 COMPLETED_WITH_ERRORS
     *
     * @param runId 任务ID
     */
    private void executeRun(long runId) {
        // CAS 更新状态：只有 QUEUED 状态才能变为 RUNNING，防止重复执行
        if (!repository.markRunning(runId)) {
            return;
        }

        Timer.Sample runTimer = Timer.start(meterRegistry);
        try {
            // 获取任务信息和配置
            EvaluationRepository.EvalRun run = repository.findRun(runId).orElseThrow();
            EvaluationConfig config = parseConfig(run.configJson());
            Instant deadline = run.startedAt() == null
                    ? Instant.now().plusSeconds(config.timeoutSeconds())
                    : run.startedAt().plusSeconds(config.timeoutSeconds());

            // 获取已完成的用例ID（用于断点续跑）
            Set<Long> completedCaseIds = repository.listCompletedCaseIds(runId);

            // 遍历所有测试用例
            for (EvaluationRepository.EvalCase evalCase : repository.listRunCases(runId)) {
                if (!repository.isRunning(runId)) {
                    return;
                }
                if (Instant.now().isAfter(deadline)) {
                    repository.timeoutRun(runId);
                    recordRunMetric(runTimer, "TIMEOUT");
                    return;
                }
                if (completedCaseIds.contains(evalCase.id())) {
                    continue; // 跳过已完成的用例
                }
                // 执行单个用例的评测
                CaseResult result = evaluateCase(evalCase, config);
                if (!repository.isRunning(runId)) {
                    return;
                }
                // 保存结果
                repository.insertResult(runId, result);
            }

            if (!repository.isRunning(runId)) {
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                repository.timeoutRun(runId);
                recordRunMetric(runTimer, "TIMEOUT");
                return;
            }

            // 获取所有结果，计算汇总指标
            List<CaseResult> results = repository.listResults(runId);

            // 检索指标：计算所有用例的 firstRelevantRank，然后计算 Hit@K 和 MRR
            List<Integer> firstRanks = results.stream().map(CaseResult::firstRelevantRank).toList();
            RetrievalMetrics metrics = RetrievalMetrics.calculate(firstRanks);

            // 生成质量指标：faithfulness 和 answerCorrectness 的平均值
            Double faithfulness = average(results.stream().map(CaseResult::faithfulness).toList());
            Double answerCorrectness = average(results.stream().map(CaseResult::answerCorrectness).toList());

            // 判断是否有错误的用例
            String status = results.stream().anyMatch(result -> result.errorMessage() != null)
                    ? "COMPLETED_WITH_ERRORS"
                    : "COMPLETED";

            // 更新任务为完成状态，保存所有汇总指标
            repository.completeRun(
                    runId, status, run.totalCases(),
                    metrics.hitAtK(), metrics.mrr(),
                    faithfulness, answerCorrectness, null);

            // 记录监控指标
            recordRunMetric(runTimer, status);

        } catch (Exception e) {
            // 发生异常时标记任务失败
            if (repository.isRunning(runId)) {
                repository.failRun(runId, safeErrorMessage(e));
                recordRunMetric(runTimer, "FAILED");
            }
        }
    }

    /**
     * 获取某知识库下的所有评测任务列表
     *
     * @param kbId 知识库ID
     * @return 任务摘要列表
     */
    public List<RunSummary> listRuns(Long kbId) {
        return repository.listRuns(kbId);
    }

    public List<CaseResult> listResults(long runId) {
        if (repository.findRun(runId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "评测运行不存在");
        }
        return repository.listResults(runId);
    }

    public void cancel(long runId) {
        if (repository.findRun(runId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "评测运行不存在");
        }
        if (!repository.cancelRun(runId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评测已进入终态，无法取消");
        }
    }

    /**
     * 获取单个评测任务的详细状态
     *
     * @param runId 任务ID
     * @return 任务详细状态（包含所有指标）
     */
    public RunStatus getRun(long runId) {
        EvaluationRepository.EvalRun run = repository.findRun(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "评测运行不存在"));
        try {
            return new RunStatus(
                    run.id(), run.kbId(), run.name(), run.status(),
                    parseConfig(run.configJson()),
                    run.totalCases(), run.completedCases(),
                    run.hitAtK(), run.mrr(),
                    run.faithfulness(), run.answerCorrectness(),
                    run.startedAt(), run.completedAt(), run.errorMessage());
        } catch (Exception e) {
            throw new IllegalStateException("评测配置解析失败", e);
        }
    }

    /**
     * 评测单个测试用例（核心评测逻辑）
     *
     * 流程：
     * 1. 用问题检索知识库，获取相关文档
     * 2. 计算 firstRelevantRank（第一个相关文档在结果中的位置）
     * 3. 如果配置要求生成答案，则调用生成模型生成答案
     * 4. 调用裁判模型对答案打分（faithfulness + answerCorrectness）
     * 5. 返回完整的评测结果
     *
     * @param evalCase  测试用例
     * @param config    评测配置
     * @return 用例评测结果
     */
    private CaseResult evaluateCase(EvaluationRepository.EvalCase evalCase, EvaluationConfig config) {
        long started = System.nanoTime();
        try {
            // 1. 执行检索
            List<RetrievalResult> retrieved = searchService.search(
                    evalCase.kbId(), evalCase.question(), config.toSearchOptions());

            // 提取返回文档的 documentId 列表
            List<Long> returnedIds = retrieved.stream()
                    .map(result -> result.metadata().get("documentId"))
                    .filter(Number.class::isInstance)
                    .map(value -> ((Number) value).longValue())
                    .distinct()
                    .toList();

            // 2. 计算第一个相关文档的排名（用于 MRR 和 Hit@K）
            int firstRank = firstRelevantRank(returnedIds, new HashSet<>(evalCase.goldenDocumentIds()));

            // 3. 生成答案和裁判打分（如果配置要求）
            String answer = null;
            Double faithfulness = null;
            Double answerCorrectness = null;

            if (config.shouldEvaluateGeneration()) {
                // 生成答案
                answer = generateAnswer(evalCase.question(), retrieved);
                // 裁判打分
                JudgeScores scores = judge(
                        evalCase.question(), evalCase.expectedAnswer(), answer, retrieved);
                faithfulness = scores.faithfulness();
                // 如果没有参考答案，则不计算 answerCorrectness
                answerCorrectness = evalCase.expectedAnswer() == null || evalCase.expectedAnswer().isBlank()
                        ? null
                        : scores.answerCorrectness();
            }

            return new CaseResult(
                    evalCase.id(),
                    evalCase.question(),
                    firstRank,
                    // 用于计算 MRR
                    firstRank == 0 ? 0.0 : 1.0 / firstRank,
                    returnedIds,
                    answer,
                    faithfulness,
                    answerCorrectness,
                    elapsedMs(started),
                    null);

        } catch (Exception e) {
            // 用例执行失败，返回错误信息
            return new CaseResult(
                    evalCase.id(), evalCase.question(), 0, 0.0,
                    List.of(), null, null, null,
                    elapsedMs(started), e.getMessage());
        }
    }

    /**
     * 调用生成模型，根据检索到的资料回答问题
     *
     * @param question   用户问题
     * @param retrieved  检索到的相关文档列表
     * @return 模型生成的答案
     */
    private String generateAnswer(String question, List<RetrievalResult> retrieved) {
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", GENERATION_SYSTEM),
                new ChatMessage("user", "资料：\n" + context(retrieved) + "\n\n问题：" + question));
        ChatCompletionResponse response = chatClient.complete(new ChatCompletionRequest(
                "deepseek", null, messages, Map.of("temperature", 0.0)));
        return response.content() == null ? "" : response.content().trim();
    }

    /**
     * 调用裁判模型（Judge Model），对生成的答案进行质量评估
     *
     * @param question         用户问题
     * @param expectedAnswer   期望答案（参考答案）
     * @param answer           模型实际生成的答案
     * @param retrieved        检索到的资料
     * @return 打分结果（faithfulness 和 answerCorrectness）
     */
    private JudgeScores judge(
            String question, String expectedAnswer, String answer, List<RetrievalResult> retrieved) {
        String reference = expectedAnswer == null || expectedAnswer.isBlank() ? "（无参考答案）" : expectedAnswer;
        String prompt = "问题：" + question
                + "\n参考答案：" + reference
                + "\n模型答案：" + answer
                + "\n资料：\n" + context(retrieved);

        ChatCompletionResponse response = chatClient.complete(new ChatCompletionRequest(
                "deepseek", null, List.of(
                new ChatMessage("system", JUDGE_SYSTEM),
                new ChatMessage("user", prompt)),
                Map.of("temperature", 0.0)));

        String raw = response.content() == null ? "{}" : response.content().trim();
        try {
            JsonNode json = objectMapper.readTree(stripCodeFence(raw));
            return new JudgeScores(
                    clamp(json.path("faithfulness").asDouble(0.0)),
                    clamp(json.path("answerCorrectness").asDouble(0.0)));
        } catch (Exception e) {
            throw new IllegalStateException("评测裁判返回非法 JSON: " + raw, e);
        }
    }

    /**
     * 构建检索资料的上下文字符串，用于拼接到 prompt 中
     * 限制最多 20 个文档，避免上下文过长
     */
    private String context(List<RetrievalResult> retrieved) {
        return retrieved.stream()
                .limit(20)
                .map(result -> "[文档 " + result.metadata().get("documentId") + "] " + result.content())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 计算第一个相关文档在检索结果中的排名
     *
     * @param returnedIds   检索返回的文档ID列表（按相关性排序）
     * @param goldenIds     测试用例中标注的相关文档ID集合
     * @return 第一个相关文档的排名（从1开始），如果没有相关文档则返回0
     */
    private static int firstRelevantRank(List<Long> returnedIds, Set<Long> goldenIds) {
        for (int i = 0; i < returnedIds.size(); i++) {
            if (goldenIds.contains(returnedIds.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * 计算列表的平均值，忽略 null 值
     */
    private static Double average(List<Double> values) {
        List<Double> present = values.stream().filter(value -> value != null).toList();
        return present.isEmpty() ? null : present.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /* ===== 序列化/反序列化辅助方法 ===== */

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("评测配置序列化失败", e);
        }
    }

    private EvaluationConfig parseConfig(String value) {
        try {
            return objectMapper.readValue(value, EvaluationConfig.class).normalized();
        } catch (Exception e) {
            throw new IllegalStateException("评测配置解析失败", e);
        }
    }

    /* ===== 幂等性相关方法 ===== */

    /**
     * 生成请求指纹，用于幂等性判断
     * 基于：kbId + name + config + 所有用例ID
     * 使用 SHA-256 生成哈希值
     */
    private String fingerprint(
            Long kbId, String name, EvaluationConfig config, List<EvaluationRepository.EvalCase> cases) {
        String source = kbId + "\n" + name + "\n" + toJson(config) + "\n"
                + cases.stream().map(EvaluationRepository.EvalCase::id).map(String::valueOf).collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("生成评测幂等指纹失败", e);
        }
    }

    /**
     * 规范化 Idempotency-Key
     * - 去除首尾空白
     * - 检查长度不超过 128 字符
     */
    private static String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String key = value.trim();
        if (key.length() > 128) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Idempotency-Key 不能超过 128 个字符");
        }
        return key;
    }

    /* ===== 其他工具方法 ===== */

    /**
     * 去除 Markdown 代码块标记（```json ... ```）
     * 用于处理裁判模型可能输出的带代码块的 JSON
     */
    private static String stripCodeFence(String value) {
        if (value.startsWith("```") && value.endsWith("```")) {
            int firstLineEnd = value.indexOf('\n');
            return firstLineEnd < 0 ? value : value.substring(firstLineEnd + 1, value.length() - 3).trim();
        }
        return value;
    }

    private static double clamp(double value) {
        return Math.clamp(value, 0.0, 1.0);
    }

    private static int elapsedMs(long started) {
        return (int) Math.min(Integer.MAX_VALUE, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private static String safeErrorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    /**
     * 记录监控指标（任务执行次数和执行时长）
     */
    private void recordRunMetric(Timer.Sample timer, String status) {
        String normalizedStatus = status.toLowerCase(java.util.Locale.ROOT);
        Counter.builder("rag2agent.evaluation.runs")
                .tag("status", normalizedStatus)
                .register(meterRegistry)
                .increment();
        timer.stop(Timer.builder("rag2agent.evaluation.duration")
                .tag("status", normalizedStatus)
                .publishPercentileHistogram()
                .register(meterRegistry));
    }

    /**
     * 裁判打分的内部记录
     */
    private record JudgeScores(Double faithfulness, Double answerCorrectness) {}
}
