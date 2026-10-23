package com.rag2agent.bootstrap.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.rag2agent.bootstrap.service.AgentMemoryService;
import com.rag2agent.bootstrap.service.AgentMemoryService.MemoryRecord;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import com.rag2agent.framework.common.ApiResponse;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/memories")
public class AgentMemoryController {

    private final AgentMemoryService memoryService;
    private final KnowledgeBaseService knowledgeBaseService;

    public AgentMemoryController(
            AgentMemoryService memoryService, KnowledgeBaseService knowledgeBaseService) {
        this.memoryService = memoryService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @GetMapping
    public ApiResponse<List<MemoryRecord>> list(
            @RequestParam(required = false) Long kbId,
            @RequestParam(defaultValue = "50") int limit) {
        Long userId = StpUtil.getLoginIdAsLong();
        if (kbId != null) {
            knowledgeBaseService.requireReadable(userId, kbId);
        }
        return ApiResponse.success(memoryService.list(userId, kbId, limit));
    }

    @GetMapping("/search")
    public ApiResponse<List<MemoryRecord>> search(
            @RequestParam String query,
            @RequestParam(required = false) Long kbId,
            @RequestParam(defaultValue = "5") int topK) {
        Long userId = StpUtil.getLoginIdAsLong();
        if (kbId != null) {
            knowledgeBaseService.requireReadable(userId, kbId);
        }
        return ApiResponse.success(memoryService.search(userId, kbId, query, topK));
    }

    @GetMapping("/review")
    public ApiResponse<List<MemoryRecord>> reviewQueue(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(
                memoryService.listForReview(StpUtil.getLoginIdAsLong(), limit));
    }

    @PostMapping("/review/{memoryId}")
    public ApiResponse<Boolean> review(
            @PathVariable Long memoryId,
            @RequestBody ReviewRequest request) {
        boolean updated = memoryService.review(
                StpUtil.getLoginIdAsLong(), memoryId, request.approved());
        if (!updated) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "待审核记忆不存在");
        }
        return ApiResponse.success(true);
    }

    @DeleteMapping("/{memoryId}")
    public ApiResponse<Boolean> delete(@PathVariable Long memoryId) {
        int deleted = memoryService.forget(StpUtil.getLoginIdAsLong(), memoryId);
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "记忆不存在");
        }
        return ApiResponse.success(true);
    }

    public record ReviewRequest(boolean approved) {}
}
