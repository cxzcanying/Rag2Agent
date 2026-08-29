package com.rag2agent.bootstrap.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.dto.DocumentDtos.DocumentView;
import com.rag2agent.bootstrap.dto.DocumentDtos.PresignResponse;
import com.rag2agent.bootstrap.service.DocumentService;
import com.rag2agent.bootstrap.service.IdempotencyService;
import com.rag2agent.framework.common.ApiResponse;
import java.util.List;
import java.util.Map;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
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
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public DocumentController(DocumentService documentService, IdempotencyService idempotency, ObjectMapper objectMapper) {
        this.documentService = documentService;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/upload")
    public ApiResponse<DocumentView> upload(
            @RequestParam("file") MultipartFile file, @RequestParam("kbId") Long kbId,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String key) {
        Long userId = StpUtil.getLoginIdAsLong();
        Map<String, Object> request = Map.of("kbId", String.valueOf(kbId), "name", file == null ? "" : String.valueOf(file.getOriginalFilename()),
                "size", file == null ? 0 : file.getSize(), "sha256", checksum(file));
        String cached = idempotency.reserve(userId, "document:upload", key, request);
        if (cached != null) {
            try { return ApiResponse.success(objectMapper.readValue(cached, DocumentView.class)); }
            catch (Exception exception) { throw new IllegalStateException("幂等响应读取失败", exception); }
        }
        try {
            DocumentView result = documentService.upload(userId, kbId, file);
            idempotency.complete(userId, "document:upload", key, result);
            return ApiResponse.success(result);
        } catch (RuntimeException exception) {
            idempotency.release(userId, "document:upload", key);
            throw exception;
        }
    }

    private String checksum(MultipartFile file) {
        if (file == null) return "";
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new com.rag2agent.framework.exception.BusinessException(
                    com.rag2agent.framework.common.ErrorCode.BAD_REQUEST, "文件指纹计算失败");
        }
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
