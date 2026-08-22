package com.rag2agent.bootstrap.controller;

import com.rag2agent.bootstrap.dto.EvaluationDtos.ImportCasesRequest;
import com.rag2agent.bootstrap.dto.EvaluationDtos.MatrixRequest;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunRequest;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunStatus;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunSubmission;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunSummary;
import com.rag2agent.bootstrap.evaluation.EvaluationService;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import cn.dev33.satoken.stp.StpUtil;
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
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;

@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final KnowledgeBaseService knowledgeBaseService;

    public EvaluationController(EvaluationService evaluationService, KnowledgeBaseService knowledgeBaseService) {
        this.evaluationService = evaluationService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping("/cases/import")
    public ApiResponse<Integer> importCases(@RequestBody @Valid ImportCasesRequest request) {
        knowledgeBaseService.requireOwned(StpUtil.getLoginIdAsLong(), request.kbId());
        return ApiResponse.success(evaluationService.importCases(request.kbId(), request.cases()));
    }

    @PostMapping("/runs")
    public ApiResponse<RunSubmission> run(
            @RequestBody @Valid RunRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        knowledgeBaseService.requireOwned(StpUtil.getLoginIdAsLong(), request.kbId());
        return ApiResponse.success(evaluationService.submit(
                request.kbId(), request.name(), request.config(), idempotencyKey));
    }

    @PostMapping("/matrix")
    public ApiResponse<List<RunSubmission>> matrix(
            @RequestBody @Valid MatrixRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        knowledgeBaseService.requireOwned(StpUtil.getLoginIdAsLong(), request.kbId());
        return ApiResponse.success(evaluationService.submitMatrix(
                request.kbId(), request.namePrefix(), request.configs(), idempotencyKey));
    }

    @GetMapping("/runs")
    public ApiResponse<List<RunSummary>> listRuns(@RequestParam Long kbId) {
        if (kbId == null || kbId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "kbId 必须为正数");
        }
        knowledgeBaseService.requireOwned(StpUtil.getLoginIdAsLong(), kbId);
        return ApiResponse.success(evaluationService.listRuns(kbId));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<RunStatus> getRun(@PathVariable Long runId) {
        if (runId == null || runId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "runId 必须为正数");
        }
        RunStatus status = evaluationService.getRun(runId);
        knowledgeBaseService.requireOwned(StpUtil.getLoginIdAsLong(), status.kbId());
        return ApiResponse.success(status);
    }
}
