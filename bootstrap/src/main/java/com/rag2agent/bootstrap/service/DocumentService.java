package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.dto.DocumentDtos.DocumentView;
import com.rag2agent.bootstrap.dto.DocumentDtos.PresignResponse;
import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.bootstrap.mapper.DocumentMetaMapper;
import com.rag2agent.bootstrap.storage.MinioStorageService;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "txt", "md", "docx");
    private static final long MAX_SIZE = 50L * 1024 * 1024;
    private static final int PRESIGN_EXPIRY_SECONDS = 600;

    private final DocumentMetaMapper documentMapper;
    private final MinioStorageService storage;
    private final IngestTaskService ingestTaskService;
    private final IngestMessageService ingestMessageService;

    public DocumentService(
            DocumentMetaMapper documentMapper,
            MinioStorageService storage,
            IngestTaskService ingestTaskService,
            IngestMessageService ingestMessageService) {
        this.documentMapper = documentMapper;
        this.storage = storage;
        this.ingestTaskService = ingestTaskService;
        this.ingestMessageService = ingestMessageService;
    }

    public DocumentView upload(Long kbId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件大小不能超过 50MB");
        }
        String fileName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename());
        String ext = extensionOf(fileName);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持 pdf/txt/md/docx 文件");
        }

        String objectKey =
                kbId + "/" + LocalDate.now() + "/" + UUID.randomUUID() + "." + ext;
        try (InputStream input = file.getInputStream()) {
            storage.upload(objectKey, input, file.getSize(),
                    file.getContentType() == null ? "application/octet-stream" : file.getContentType()); //设置文件类型默认为application/octet-stream
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件上传 MinIO 失败: " + e.getMessage());
        }

        DocumentMeta doc = new DocumentMeta();
        doc.setKbId(kbId);
        doc.setFileName(fileName);
        doc.setFileType(ext);
        doc.setStoragePath(objectKey);
        doc.setFileSize(file.getSize());
        doc.setVersion(1);
        doc.setStatus("UPLOADED");
        documentMapper.insert(doc);
        ingestTaskService.create(doc.getId());
        ingestMessageService.sendIngestTask(doc.getId());
        return DocumentView.from(doc);
    }

    public List<DocumentView> listByKb(Long kbId) {
        return documentMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentMeta>()
                                .eq(DocumentMeta::getKbId, kbId)
                                .orderByDesc(DocumentMeta::getId))
                .stream()
                .map(DocumentView::from)
                .toList();
    }

    public PresignResponse presign(Long documentId) {
        DocumentMeta doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        try {
            String url = storage.presignGet(doc.getStoragePath(), PRESIGN_EXPIRY_SECONDS);
            return new PresignResponse(url, PRESIGN_EXPIRY_SECONDS);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成下载链接失败: " + e.getMessage());
        }
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
