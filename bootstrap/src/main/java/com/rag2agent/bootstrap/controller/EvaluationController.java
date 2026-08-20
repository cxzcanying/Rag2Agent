package com.rag2agent.bootstrap.controller;

import com.rag2agent.bootstrap.dto.EvaluationDtos.ImportCasesRequest;
import com.rag2agent.bootstrap.dto.EvaluationDtos.MatrixRequest;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunRequest;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunStatus;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunSubmission;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunSummary;
import com.rag2agent.bootstrap.evaluation.EvaluationService;
import com.rag2agent.framework.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping("/cases/import")
    public ApiResponse<Integer> importCases(@RequestBody @Valid ImportCasesRequest request) {
        return ApiResponse.success(evaluationService.importCases(request.kbId(), request.cases()));
    }

    @PostMapping("/runs")
    public ApiResponse<RunSubmission> run(
            @RequestBody @Valid RunRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.success(evaluationService.submit(
                request.kbId(), request.name(), request.config(), idempotencyKey));
    }

    @PostMapping("/matrix")
    public ApiResponse<List<RunSubmission>> matrix(
            @RequestBody @Valid MatrixRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.success(evaluationService.submitMatrix(
                request.kbId(), request.namePrefix(), request.configs(), idempotencyKey));
    }

    @GetMapping("/runs")
    public ApiResponse<List<RunSummary>> listRuns(@RequestParam Long kbId) {
        return ApiResponse.success(evaluationService.listRuns(kbId));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<RunStatus> getRun(@PathVariable Long runId) {
        return ApiResponse.success(evaluationService.getRun(runId));
    }
}
