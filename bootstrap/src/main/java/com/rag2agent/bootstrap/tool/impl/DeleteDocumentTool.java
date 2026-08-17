package com.rag2agent.bootstrap.tool.impl;

import com.rag2agent.bootstrap.mapper.DocumentChunkMapper;
import com.rag2agent.bootstrap.mapper.DocumentMetaMapper;
import com.rag2agent.bootstrap.tool.Tool;
import com.rag2agent.bootstrap.tool.ToolDescriptor;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 高风险写工具：删除文档及其所有切块，需要人工审批。
 */
@Component
public class DeleteDocumentTool implements Tool {

    private final DocumentMetaMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;

    public DeleteDocumentTool(DocumentMetaMapper documentMapper, DocumentChunkMapper chunkMapper) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
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
    public String execute(Map<String, Object> arguments) {
        Long documentId = ((Number) arguments.get("document_id")).longValue();
        // 先删 chunk 再删 document，避免外键约束
        chunkMapper.deleteByDocument(documentId);
        int deleted = documentMapper.deleteById(documentId);
        return deleted == 0
                ? "文档不存在或已删除"
                : "文档 " + documentId + " 及其切块已删除";
    }
}
