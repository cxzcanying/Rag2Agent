package com.rag2agent.bootstrap.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.rag2agent.bootstrap.security.AdminAccessService;
import com.rag2agent.bootstrap.service.CostReportService;
import com.rag2agent.bootstrap.service.TokenCostService;
import com.rag2agent.framework.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/costs")
public class CostController {

    private final CostReportService service;
    private final AdminAccessService adminAccessService;
    private final TokenCostService tokenCostService;

    public CostController(
            CostReportService service,
            AdminAccessService adminAccessService,
            TokenCostService tokenCostService) {
        this.service = service;
        this.adminAccessService = adminAccessService;
        this.tokenCostService = tokenCostService;
    }

    @GetMapping("/summary")
    public ApiResponse<CostReportService.CostReport> summary(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String function) {
        return ApiResponse.success(service.summary(
                StpUtil.getLoginIdAsLong(), userId, from, to, model, function));
    }

    @GetMapping("/budget")
    public ApiResponse<TokenCostService.BudgetStatus> budget() {
        return ApiResponse.success(tokenCostService.budgetStatus());
    }

    @PostMapping("/balances")
    public ApiResponse<Void> balance(
            @RequestBody @Valid BalanceRequest request,
            HttpServletRequest httpRequest) {
        adminAccessService.requireAccess(httpRequest, false);
        service.recordBalance(
                request.provider(), request.currency(), request.balance(), request.source());
        return ApiResponse.success(null);
    }

    public record BalanceRequest(
            @NotBlank String provider,
            @NotBlank String currency,
            BigDecimal balance,
            String source) {}
}
