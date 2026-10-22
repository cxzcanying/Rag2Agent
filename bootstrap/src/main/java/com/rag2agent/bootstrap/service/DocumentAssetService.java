package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.dto.AssetDtos.AssetView;
import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.bootstrap.service.DocumentStructureRepository.AssetRecord;
import com.rag2agent.bootstrap.storage.MinioStorageService;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class DocumentAssetService {

    private static final int PRESIGN_EXPIRY_SECONDS = 600;

    private final DocumentService documentService;
    private final DocumentStructureRepository repository;
    private final MinioStorageService storage;

    public DocumentAssetService(
            DocumentService documentService,
            DocumentStructureRepository repository,
            MinioStorageService storage) {
        this.documentService = documentService;
        this.repository = repository;
        this.storage = storage;
    }

    public AssetView presign(
            Long userId, Long documentId, String assetId, Integer requestedVersion) {
        DocumentMeta document = documentService.requireOwnedDocument(userId, documentId);
        int version = requestedVersion == null ? document.getVersion() : requestedVersion;
        AssetRecord asset = repository.findAsset(documentId, version, assetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文档资产不存在"));
        if (asset.contentReference() == null || asset.contentReference().isBlank()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "资产内容不可用");
        }
        try {
            return AssetView.from(
                    asset,
                    storage.presignGet(asset.contentReference(), PRESIGN_EXPIRY_SECONDS),
                    PRESIGN_EXPIRY_SECONDS);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成资产访问链接失败");
        }
    }
}
