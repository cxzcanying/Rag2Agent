package com.rag2agent.bootstrap.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.rag2agent.bootstrap.graph.GraphCommunityService;
import com.rag2agent.bootstrap.graph.KnowledgeGraphRepository.CommunitySummary;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import com.rag2agent.framework.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/{kbId}/graph")
public class KnowledgeGraphController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final GraphCommunityService communityService;

    public KnowledgeGraphController(
            KnowledgeBaseService knowledgeBaseService,
            GraphCommunityService communityService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.communityService = communityService;
    }

    @PostMapping("/communities/refresh")
    public ApiResponse<Integer> refresh(@PathVariable Long kbId) {
        knowledgeBaseService.requireWritable(StpUtil.getLoginIdAsLong(), kbId);
        return ApiResponse.success(communityService.refresh(kbId));
    }

    @GetMapping("/communities")
    public ApiResponse<List<CommunitySummary>> communities(
            @PathVariable Long kbId,
            @RequestParam(defaultValue = "10") int limit) {
        knowledgeBaseService.requireReadable(StpUtil.getLoginIdAsLong(), kbId);
        return ApiResponse.success(communityService.globalSummaries(kbId, limit));
    }
}
