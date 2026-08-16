package com.rag2agent.bootstrap.controller;

import com.rag2agent.bootstrap.service.HybridSearchService;
import com.rag2agent.framework.common.ApiResponse;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final HybridSearchService searchService;

    public SearchController(HybridSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public ApiResponse<List<RetrievalResult>> search(
            @RequestParam Long kbId,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        return ApiResponse.success(searchService.search(kbId, query, topK));
    }
}
