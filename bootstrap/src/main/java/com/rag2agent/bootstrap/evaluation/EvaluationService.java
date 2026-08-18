package com.rag2agent.bootstrap.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.dto.EvaluationDtos.CaseInput;
import com.rag2agent.bootstrap.dto.EvaluationDtos.CaseResult;
import com.rag2agent.bootstrap.dto.EvaluationDtos.EvaluationConfig;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunReport;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationService {

    private static final String GENERATION_SYSTEM = """
            你是企业知识库评测中的回答模型。只能依据给定资料回答，资料不足时明确说不知道。
            不要输出引用之外的事实。
            """;
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

    public EvaluationService(
            EvaluationRepository repository,
            HybridSearchService searchService,
            ChatModelClient chatClient,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.searchService = searchService;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public int importCases(Long kbId, List<CaseInput> cases) {
        return repository.insertCases(kbId, cases);
    }

    public RunReport run(Long kbId, String name, EvaluationConfig config) {
        EvaluationConfig effectiveConfig = config.normalized();
        List<EvaluationRepository.EvalCase> cases = repository.listCases(kbId);
        if (cases.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该知识库没有评测用例，请先导入 cases");
        }
        long runId = repository.createRun(kbId, name, toJson(effectiveConfig));
        Timer.Sample runTimer = Timer.start(meterRegistry);
        List<CaseResult> results = new ArrayList<>();
        List<Integer> firstRanks = new ArrayList<>();
        try {
            for (EvaluationRepository.EvalCase evalCase : cases) {
                CaseResult result = evaluateCase(evalCase, effectiveConfig);
                results.add(result);
                firstRanks.add(result.firstRelevantRank());
                repository.insertResult(runId, result);
            }
            RetrievalMetrics metrics = RetrievalMetrics.calculate(firstRanks);
            Double faithfulness = average(results.stream().map(CaseResult::faithfulness).toList());
            Double answerCorrectness = average(results.stream().map(CaseResult::answerCorrectness).toList());
            String status = results.stream().anyMatch(result -> result.errorMessage() != null)
                    ? "COMPLETED_WITH_ERRORS"
                    : "COMPLETED";
            repository.completeRun(
                    runId, status, metrics.totalCases(), metrics.hitAtK(), metrics.mrr(),
                    faithfulness, answerCorrectness, null);
            recordRunMetric(runTimer, status);
            return new RunReport(
                    runId, kbId, name, status, effectiveConfig, metrics.totalCases(), metrics.hitCases(),
                    metrics.hitAtK(), metrics.mrr(), faithfulness, answerCorrectness, results);
        } catch (Exception e) {
            repository.completeRun(runId, "FAILED", results.size(), 0.0, 0.0, null, null, e.getMessage());
            recordRunMetric(runTimer, "FAILED");
            throw e;
        }
    }

    public List<RunReport> runMatrix(Long kbId, String namePrefix, List<EvaluationConfig> configs) {
        List<RunReport> reports = new ArrayList<>();
        for (int i = 0; i < configs.size(); i++) {
            reports.add(run(kbId, namePrefix + "-" + (i + 1), configs.get(i)));
        }
        return reports;
    }

    public List<RunSummary> listRuns(Long kbId) {
        return repository.listRuns(kbId);
    }

    private CaseResult evaluateCase(EvaluationRepository.EvalCase evalCase, EvaluationConfig config) {
        long started = System.nanoTime();
        try {
            List<RetrievalResult> retrieved = searchService.search(
                    evalCase.kbId(), evalCase.question(), config.toSearchOptions());
            List<Long> returnedIds = retrieved.stream()
                    .map(result -> result.metadata().get("documentId"))
                    .filter(Number.class::isInstance)
                    .map(value -> ((Number) value).longValue())
                    .distinct()
                    .toList();
            int firstRank = firstRelevantRank(returnedIds, new HashSet<>(evalCase.goldenDocumentIds()));
            String answer = null;
            Double faithfulness = null;
            Double answerCorrectness = null;
            if (config.shouldEvaluateGeneration()) {
                answer = generateAnswer(evalCase.question(), retrieved);
                JudgeScores scores = judge(
                        evalCase.question(), evalCase.expectedAnswer(), answer, retrieved);
                faithfulness = scores.faithfulness();
                answerCorrectness = evalCase.expectedAnswer() == null || evalCase.expectedAnswer().isBlank()
                        ? null
                        : scores.answerCorrectness();
            }
            return new CaseResult(
                    evalCase.id(), evalCase.question(), firstRank, firstRank == 0 ? 0.0 : 1.0 / firstRank,
                    returnedIds, answer, faithfulness, answerCorrectness, elapsedMs(started), null);
        } catch (Exception e) {
            return new CaseResult(
                    evalCase.id(), evalCase.question(), 0, 0.0, List.of(), null, null, null,
                    elapsedMs(started), e.getMessage());
        }
    }

    private String generateAnswer(String question, List<RetrievalResult> retrieved) {
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", GENERATION_SYSTEM),
                new ChatMessage("user", "资料：\n" + context(retrieved) + "\n\n问题：" + question));
        ChatCompletionResponse response = chatClient.complete(new ChatCompletionRequest(
                "deepseek", null, messages, Map.of("temperature", 0.0)));
        return response.content() == null ? "" : response.content().trim();
    }

    private JudgeScores judge(
            String question, String expectedAnswer, String answer, List<RetrievalResult> retrieved) {
        String reference = expectedAnswer == null || expectedAnswer.isBlank() ? "（无参考答案）" : expectedAnswer;
        String prompt = "问题：" + question
                + "\n参考答案：" + reference
                + "\n模型答案：" + answer
                + "\n资料：\n" + context(retrieved);
        ChatCompletionResponse response = chatClient.complete(new ChatCompletionRequest(
                "deepseek", null, List.of(
                        new ChatMessage("system", JUDGE_SYSTEM), new ChatMessage("user", prompt)),
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

    private String context(List<RetrievalResult> retrieved) {
        return retrieved.stream()
                .limit(20)
                .map(result -> "[文档 " + result.metadata().get("documentId") + "] " + result.content())
                .collect(Collectors.joining("\n"));
    }

    private static int firstRelevantRank(List<Long> returnedIds, Set<Long> goldenIds) {
        for (int i = 0; i < returnedIds.size(); i++) {
            if (goldenIds.contains(returnedIds.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    private static Double average(List<Double> values) {
        List<Double> present = values.stream().filter(value -> value != null).toList();
        return present.isEmpty() ? null : present.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("评测配置序列化失败", e);
        }
    }

    private static String stripCodeFence(String value) {
        if (value.startsWith("```") && value.endsWith("```")) {
            int firstLineEnd = value.indexOf('\n');
            return firstLineEnd < 0 ? value : value.substring(firstLineEnd + 1, value.length() - 3).trim();
        }
        return value;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int elapsedMs(long started) {
        return (int) Math.min(Integer.MAX_VALUE, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

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

    private record JudgeScores(Double faithfulness, Double answerCorrectness) {}
}
