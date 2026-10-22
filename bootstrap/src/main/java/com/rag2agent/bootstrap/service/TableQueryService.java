package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.dto.TableDtos.TablePage;
import com.rag2agent.bootstrap.dto.TableDtos.TableRowView;
import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.bootstrap.service.DocumentStructureRepository.RowRecord;
import com.rag2agent.bootstrap.service.DocumentStructureRepository.TableRecord;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TableQueryService {

    private final DocumentService documentService;
    private final DocumentStructureRepository repository;

    public TableQueryService(DocumentService documentService, DocumentStructureRepository repository) {
        this.documentService = documentService;
        this.repository = repository;
    }

    public TablePage page(
            Long userId,
            Long documentId,
            String tableId,
            Integer requestedVersion,
            String cursor,
            int limit) {
        if (tableId == null || tableId.isBlank() || tableId.length() > 256) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "tableId 无效");
        }
        if (limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "limit 必须在 1~100 之间");
        }
        DocumentMeta document = documentService.requireOwnedDocument(userId, documentId);
        int version = requestedVersion == null ? document.getVersion() : requestedVersion;
        if (version <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "version 必须为正数");
        }
        TableRecord table = repository.findTable(documentId, version, tableId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "表格不存在"));
        int afterRowIndex = decodeCursor(cursor);
        List<RowRecord> fetched = repository.findRows(
                documentId, version, tableId, afterRowIndex, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<RowRecord> pageRows = hasMore ? fetched.subList(0, limit) : fetched;
        List<TableRowView> rows = pageRows.stream().map(TableRowView::from).toList();
        int rowStart = rows.isEmpty() ? afterRowIndex + 1 : rows.getFirst().rowIndex();
        int rowEnd = rows.isEmpty() ? afterRowIndex : rows.getLast().rowIndex();
        return new TablePage(
                table.tableId(),
                table.title(),
                table.headers(),
                table.pageFrom(),
                table.pageTo(),
                repository.countRows(documentId, version, tableId),
                rowStart,
                rowEnd,
                hasMore,
                hasMore ? "row:" + rowEnd : null,
                rows);
    }

    private int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return -1;
        }
        if (!cursor.startsWith("row:")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "continuationCursor 无效");
        }
        try {
            int value = Integer.parseInt(cursor.substring(4));
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "continuationCursor 无效");
        }
    }
}
