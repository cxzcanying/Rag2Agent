package com.rag2agent.bootstrap.tool.impl;

import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.bootstrap.mapper.DocumentChunkMapper;
import com.rag2agent.bootstrap.mapper.DocumentMetaMapper;
import com.rag2agent.bootstrap.mapper.IngestTaskMapper;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import com.rag2agent.bootstrap.tool.Tool;
import com.rag2agent.bootstrap.tool.ToolDescriptor;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 高风险写工具：删除文档及其所有切块，需要人工审批。
 */
@Component
public class DeleteDocumentTool implements Tool {

    private final DocumentMetaMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final IngestTaskMapper ingestTaskMapper;
    private final KnowledgeBaseService knowledgeBaseService;

    public DeleteDocumentTool(
            DocumentMetaMapper documentMapper,
            DocumentChunkMapper chunkMapper,
            IngestTaskMapper ingestTaskMapper,
            KnowledgeBaseService knowledgeBaseService) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.ingestTaskMapper = ingestTaskMapper;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Override
    public void validateAccess(Long userId, Map<String, Object> arguments) {
        Long documentId = ((Number) arguments.get("document_id")).longValue();
        DocumentMeta document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        knowledgeBaseService.requireOwned(userId, document.getKbId());
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "delete_document",
                "删除指定文档及其所有切块（高风险操作，需要人工审批）",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "document_id", Map.of("type", "integer", "description", "文档 ID")),
                        "required", List.of("document_id")),
                true);
    }

    @Override
    @Transactional
    public String execute(Map<String, Object> arguments) {
        Long documentId = ((Number) arguments.get("document_id")).longValue();
        // 外键约束顺序：先删 ingest_task，再删 chunk，最后删 document
        ingestTaskMapper.deleteByDocument(documentId);
        chunkMapper.deleteByDocument(documentId);
        int deleted = documentMapper.deleteById(documentId);
        return deleted == 0
                ? "文档不存在或已删除"
                : "文档 " + documentId + " 及其切块已删除";
    }
}
