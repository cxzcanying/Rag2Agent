package com.rag2agent.bootstrap.controller;

import com.rag2agent.bootstrap.service.HybridSearchService;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import cn.dev33.satoken.stp.StpUtil;
import com.rag2agent.framework.common.ApiResponse;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 21311
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final HybridSearchService searchService;
    private final KnowledgeBaseService knowledgeBaseService;

    public SearchController(HybridSearchService searchService, KnowledgeBaseService knowledgeBaseService) {
        this.searchService = searchService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @GetMapping
    public ApiResponse<List<RetrievalResult>> search(
            @RequestParam Long kbId,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        // 入口参数防御：空 kbId 会直接进 SQL，空白 query 会白调 embedding 且 ILIKE 全匹配，异常 topK 会让 limit 抛错
        if (kbId == null || kbId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "kbId 不能为空");
        }
        knowledgeBaseService.requireOwned(StpUtil.getLoginIdAsLong(), kbId);
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "query 不能为空");
        }
        if (topK < 1 || topK > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "topK 必须在 1~100 之间");
        }
        return ApiResponse.success(searchService.search(kbId, query, topK));
    }
}
