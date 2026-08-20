package com.rag2agent.bootstrap.dto;

import com.rag2agent.bootstrap.service.SearchOptions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class EvaluationDtos {

    private EvaluationDtos() {}

    public record CaseInput(
            @NotBlank(message = "question 不能为空") String question,
            String expectedAnswer,
            @NotEmpty(message = "goldenDocumentIds 不能为空") List<@Positive Long> goldenDocumentIds) {}

    public record ImportCasesRequest(
            @NotNull(message = "kbId 不能为空") @Positive Long kbId,
            @NotEmpty(message = "cases 不能为空") @Size(max = 500) List<@Valid CaseInput> cases) {}

    public record EvaluationConfig(
            SearchOptions.Strategy strategy,
            Integer topK,
            Integer candidateTopK,
            Double rrfK,
            Boolean rerankEnabled,
            Double rerankMinScore,
            Boolean evaluateGeneration) {

        public SearchOptions toSearchOptions() {
            int normalizedTopK = topK == null ? 5 : topK;
            return new SearchOptions(
                    strategy == null ? SearchOptions.Strategy.AUTO : strategy,
                    normalizedTopK,
                    candidateTopK == null ? Math.max(20, normalizedTopK) : candidateTopK,
                    rrfK == null ? 60.0 : rrfK,
                    rerankEnabled == null || rerankEnabled,
                    rerankMinScore);
        }

        public boolean shouldEvaluateGeneration() {
            return Boolean.TRUE.equals(evaluateGeneration);
        }

        public EvaluationConfig normalized() {
            SearchOptions options = toSearchOptions();
            return new EvaluationConfig(
                    options.strategy(),
                    options.topK(),
                    options.candidateTopK(),
                    options.rrfK(),
                    options.rerankEnabled(),
                    options.rerankMinScore(),
                    shouldEvaluateGeneration());
        }
    }

    public record RunRequest(
            @NotNull(message = "kbId 不能为空") @Positive Long kbId,
            @NotBlank(message = "name 不能为空") @Size(max = 128) String name,
            @NotNull(message = "config 不能为空") @Valid EvaluationConfig config) {}

    public record MatrixRequest(
            @NotNull(message = "kbId 不能为空") @Positive Long kbId,
            @NotBlank(message = "namePrefix 不能为空") @Size(max = 96) String namePrefix,
            @NotEmpty(message = "configs 不能为空") @Size(max = 20) List<@Valid EvaluationConfig> configs) {}

    public record CaseResult(
            Long caseId,
            String question,
            int firstRelevantRank,
            double reciprocalRank,
            List<Long> returnedDocumentIds,
            String generatedAnswer,
            Double faithfulness,
            Double answerCorrectness,
            int latencyMs,
            String errorMessage) {}

    public record RunReport(
            Long runId,
            Long kbId,
            String name,
            String status,
            EvaluationConfig config,
            int totalCases,
            int hitCases,
            double hitAtK,
            double mrr,
            Double faithfulness,
            Double answerCorrectness,
            List<CaseResult> cases) {}

    public record RunSubmission(
            Long runId,
            String status,
            int totalCases,
            int completedCases,
            boolean reused) {}

    public record RunStatus(
            Long runId,
            Long kbId,
            String name,
            String status,
            EvaluationConfig config,
            int totalCases,
            int completedCases,
            Double hitAtK,
            Double mrr,
            Double faithfulness,
            Double answerCorrectness,
            Instant startedAt,
            Instant completedAt,
            String errorMessage) {}

    public record RunSummary(
            Long runId,
            Long kbId,
            String name,
            String status,
            int totalCases,
            int completedCases,
            Double hitAtK,
            Double mrr,
            Double faithfulness,
            Double answerCorrectness,
            Instant startedAt,
            Instant completedAt,
            String errorMessage) {}
}
