package com.rag2agent.bootstrap.tool.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.dto.TableDtos.TablePage;
import com.rag2agent.bootstrap.service.DocumentService;
import com.rag2agent.bootstrap.service.TableQueryService;
import com.rag2agent.bootstrap.tool.Tool;
import com.rag2agent.bootstrap.tool.ToolDescriptor;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 只读工具：分页读取逻辑表格，每页固定返回标题、表头和延续游标。 */
@Component
public class ReadTableRowsTool implements Tool {

    private static final int MAX_PAGE_SIZE = 20;

    private final DocumentService documentService;
    private final TableQueryService tableQueryService;
    private final ObjectMapper objectMapper;

    public ReadTableRowsTool(
            DocumentService documentService,
            TableQueryService tableQueryService,
            ObjectMapper objectMapper) {
        this.documentService = documentService;
        this.tableQueryService = tableQueryService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void validateAccess(Long userId, Map<String, Object> arguments) {
        documentService.requireOwnedDocument(userId, longValue(arguments, "document_id"));
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "read_table_rows",
                "分页读取指定文档中的表格行；响应包含表标题、完整表头和 continuationCursor",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "document_id", Map.of("type", "integer", "description", "文档 ID"),
                                "table_id", Map.of("type", "string", "description", "逻辑表格 ID"),
                                "version", Map.of("type", "integer", "description", "可选文档版本"),
                                "cursor", Map.of("type", "string", "description", "上一页返回的 continuationCursor"),
                                "limit", Map.of("type", "integer", "description", "每页行数，默认 20，最大 20")),
                        "required", List.of("document_id", "table_id")),
                false);
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        throw new UnsupportedOperationException("read_table_rows 需要用户上下文");
    }

    @Override
    public String execute(Long userId, Map<String, Object> arguments) {
        int limit = arguments.get("limit") == null
                ? MAX_PAGE_SIZE
                : Math.min(MAX_PAGE_SIZE, ((Number) arguments.get("limit")).intValue());
        TablePage page = tableQueryService.page(
                userId,
                longValue(arguments, "document_id"),
                (String) arguments.get("table_id"),
                arguments.get("version") == null
                        ? null
                        : ((Number) arguments.get("version")).intValue(),
                (String) arguments.get("cursor"),
                limit);
        try {
            return objectMapper.writeValueAsString(page);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("表格分页结果序列化失败", exception);
        }
    }

    private Long longValue(Map<String, Object> arguments, String name) {
        return ((Number) arguments.get(name)).longValue();
    }
}
