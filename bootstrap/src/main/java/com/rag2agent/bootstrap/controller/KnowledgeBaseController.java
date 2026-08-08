package com.rag2agent.bootstrap.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.rag2agent.bootstrap.dto.KbDtos.CreateKnowledgeBaseRequest;
import com.rag2agent.bootstrap.dto.KbDtos.KnowledgeBaseView;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import com.rag2agent.framework.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
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

    public KnowledgeBaseController(KnowledgeBaseService kbService) {
        this.kbService = kbService;
    }

    @PostMapping
    public ApiResponse<KnowledgeBaseView> create(@RequestBody @Valid CreateKnowledgeBaseRequest request) {
        //返回long类型
        return ApiResponse.success(kbService.create(StpUtil.getLoginIdAsLong(), request));

    }

    @GetMapping
    public ApiResponse<List<KnowledgeBaseView>> list() {
        return ApiResponse.success(kbService.list());
    }
}
