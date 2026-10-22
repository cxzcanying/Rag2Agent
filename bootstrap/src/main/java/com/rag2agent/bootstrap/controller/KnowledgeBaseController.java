package com.rag2agent.bootstrap.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.rag2agent.bootstrap.dto.KbDtos.CreateKnowledgeBaseRequest;
import com.rag2agent.bootstrap.dto.KbDtos.GrantKnowledgeBaseMemberRequest;
import com.rag2agent.bootstrap.dto.KbDtos.KnowledgeBaseMemberView;
import com.rag2agent.bootstrap.dto.KbDtos.KnowledgeBaseView;
import com.rag2agent.bootstrap.dto.KbDtos.UpdateKnowledgeBaseVisibilityRequest;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import com.rag2agent.bootstrap.service.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.framework.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 21311
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseController(KnowledgeBaseService kbService, IdempotencyService idempotency, ObjectMapper objectMapper) {
        this.kbService = kbService;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ApiResponse<KnowledgeBaseView> create(@RequestBody @Valid CreateKnowledgeBaseRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String key) {
        //返回long类型
        Long userId = StpUtil.getLoginIdAsLong();
        String cached = idempotency.reserve(userId, "knowledge-base:create", key, request);
        if (cached != null) {
            try { return ApiResponse.success(objectMapper.readValue(cached, KnowledgeBaseView.class)); }
            catch (Exception exception) { throw new IllegalStateException("幂等响应读取失败", exception); }
        }
        try {
            KnowledgeBaseView result = kbService.create(userId, request);
            idempotency.complete(userId, "knowledge-base:create", key, result);
            return ApiResponse.success(result);
        } catch (RuntimeException exception) {
            idempotency.release(userId, "knowledge-base:create", key);
            throw exception;
        }

    }

    @GetMapping
    public ApiResponse<List<KnowledgeBaseView>> list() {
        return ApiResponse.success(kbService.list(StpUtil.getLoginIdAsLong()));
    }

    @PatchMapping("/{kbId}/visibility")
    public ApiResponse<Void> updateVisibility(
            @PathVariable Long kbId,
            @RequestBody @Valid UpdateKnowledgeBaseVisibilityRequest request) {
        kbService.updateVisibility(StpUtil.getLoginIdAsLong(), kbId, request.visibility());
        return ApiResponse.success(null);
    }

    @GetMapping("/{kbId}/members")
    public ApiResponse<List<KnowledgeBaseMemberView>> members(@PathVariable Long kbId) {
        return ApiResponse.success(
                kbService.listMembers(StpUtil.getLoginIdAsLong(), kbId));
    }

    @PostMapping("/{kbId}/members")
    public ApiResponse<KnowledgeBaseMemberView> grant(
            @PathVariable Long kbId,
            @RequestBody @Valid GrantKnowledgeBaseMemberRequest request) {
        return ApiResponse.success(kbService.grant(
                StpUtil.getLoginIdAsLong(), kbId, request.userId(), request.role()));
    }

    @DeleteMapping("/{kbId}/members/{userId}")
    public ApiResponse<Boolean> revoke(
            @PathVariable Long kbId, @PathVariable Long userId) {
        return ApiResponse.success(
                kbService.revoke(StpUtil.getLoginIdAsLong(), kbId, userId));
    }
}
