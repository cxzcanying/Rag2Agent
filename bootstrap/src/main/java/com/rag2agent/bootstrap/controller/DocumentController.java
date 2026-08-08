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
        return ApiResponse.success(documentService.upload(kbId, file));
    }

    @GetMapping
    public ApiResponse<List<DocumentView>> list(@RequestParam("kbId") Long kbId) {
        return ApiResponse.success(documentService.listByKb(kbId));
    }

    @GetMapping("/{id}/presign")
    public ApiResponse<PresignResponse> presign(@PathVariable("id") Long id) {
        return ApiResponse.success(documentService.presign(id));
    }
}
