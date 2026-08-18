package com.rag2agent.bootstrap.controller;

import com.rag2agent.bootstrap.dto.EvaluationDtos.ImportCasesRequest;
import com.rag2agent.bootstrap.dto.EvaluationDtos.MatrixRequest;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunReport;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunRequest;
import com.rag2agent.bootstrap.dto.EvaluationDtos.RunSummary;
import com.rag2agent.bootstrap.evaluation.EvaluationService;
import com.rag2agent.framework.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    public ApiResponse<RunReport> run(@RequestBody @Valid RunRequest request) {
        return ApiResponse.success(evaluationService.run(request.kbId(), request.name(), request.config()));
    }

    @PostMapping("/matrix")
    public ApiResponse<List<RunReport>> matrix(@RequestBody @Valid MatrixRequest request) {
        return ApiResponse.success(
                evaluationService.runMatrix(request.kbId(), request.namePrefix(), request.configs()));
    }

    @GetMapping("/runs")
    public ApiResponse<List<RunSummary>> listRuns(@RequestParam Long kbId) {
        return ApiResponse.success(evaluationService.listRuns(kbId));
    }
}
