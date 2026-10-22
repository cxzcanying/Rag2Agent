package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.dto.DocumentStructureDtos.ElementPage;
import com.rag2agent.bootstrap.dto.DocumentStructureDtos.ElementView;
import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.bootstrap.service.DocumentStructureRepository.ElementRecord;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DocumentElementQueryService {

    private final DocumentService documentService;
    private final DocumentStructureRepository repository;

    public DocumentElementQueryService(
            DocumentService documentService, DocumentStructureRepository repository) {
        this.documentService = documentService;
        this.repository = repository;
    }

    public ElementPage page(
            Long userId,
            Long documentId,
            Integer requestedVersion,
            String cursor,
            int limit) {
        if (limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "limit 必须在 1~100 之间");
        }
        DocumentMeta document = documentService.requireOwnedDocument(userId, documentId);
        int version = requestedVersion == null ? document.getVersion() : requestedVersion;
        if (version <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "version 必须为正数");
        }
        int afterOrdinal = decodeCursor(cursor);
        List<ElementRecord> fetched = repository.findElements(
                documentId, version, afterOrdinal, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<ElementRecord> pageItems = hasMore ? fetched.subList(0, limit) : fetched;
        List<ElementView> elements = pageItems.stream().map(ElementView::from).toList();
        int start = elements.isEmpty() ? afterOrdinal + 1 : elements.getFirst().ordinal();
        int end = elements.isEmpty() ? afterOrdinal : elements.getLast().ordinal();
        return new ElementPage(
                documentId,
                version,
                repository.countElements(documentId, version),
                start,
                end,
                hasMore,
                hasMore ? "element:" + end : null,
                elements);
    }

    private int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return -1;
        }
        if (!cursor.startsWith("element:")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "continuationCursor 无效");
        }
        try {
            int value = Integer.parseInt(cursor.substring("element:".length()));
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "continuationCursor 无效");
        }
    }
}
