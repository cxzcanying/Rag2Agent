package com.rag2agent.bootstrap.controller;

import com.rag2agent.bootstrap.dto.DocumentDtos.DocumentView;
import com.rag2agent.bootstrap.dto.DocumentDtos.PresignResponse;
import com.rag2agent.bootstrap.service.DocumentService;
import com.rag2agent.framework.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import cn.dev33.satoken.stp.StpUtil;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/upload")
    public ApiResponse<DocumentView> upload(
            @RequestParam("file") MultipartFile file, @RequestParam("kbId") Long kbId) {
        return ApiResponse.success(documentService.upload(StpUtil.getLoginIdAsLong(), kbId, file));
    }

    @GetMapping
    public ApiResponse<List<DocumentView>> list(@RequestParam("kbId") Long kbId) {
        if (kbId == null || kbId <= 0) {
            throw new com.rag2agent.framework.exception.BusinessException(
                    com.rag2agent.framework.common.ErrorCode.BAD_REQUEST, "kbId 必须为正数");
        }
        return ApiResponse.success(documentService.listByKb(StpUtil.getLoginIdAsLong(), kbId));
    }

    @GetMapping("/{id}/presign")
    public ApiResponse<PresignResponse> presign(@PathVariable("id") Long id) {
        if (id == null || id <= 0) {
            throw new com.rag2agent.framework.exception.BusinessException(
                    com.rag2agent.framework.common.ErrorCode.BAD_REQUEST, "documentId 必须为正数");
        }
        return ApiResponse.success(documentService.presign(StpUtil.getLoginIdAsLong(), id));
    }

    @PostMapping("/{id}/reingest")
    public ApiResponse<DocumentView> reingest(@PathVariable("id") Long id) {
        return ApiResponse.success(documentService.reingest(StpUtil.getLoginIdAsLong(), id));
    }
}
