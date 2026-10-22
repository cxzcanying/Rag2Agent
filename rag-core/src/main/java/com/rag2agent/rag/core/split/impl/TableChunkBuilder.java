package com.rag2agent.rag.core.split.impl;

import com.rag2agent.rag.core.document.StructuredDocument;
import com.rag2agent.rag.core.document.StructuredDocument.Row;
import com.rag2agent.rag.core.document.StructuredDocument.Table;
import com.rag2agent.rag.core.split.TextChunk;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 为表格行生成带完整表头上下文的可检索 chunk。 */
public final class TableChunkBuilder {

    public List<TextChunk> build(StructuredDocument document, int startPosition) {
        List<TextChunk> chunks = new ArrayList<>();
        int position = startPosition;
        for (Table table : document.tables()) {
            for (Row row : table.rows()) {
                String page = row.page() == null ? "未知" : String.valueOf(row.page());
                String content = "表标题：" + fallback(table.title(), table.id())
                        + "\n表头：" + String.join(" | ", table.headers())
                        + "\n数据：" + row.semanticText()
                        + "\n页码：" + page;
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("contentType", "table_row");
                metadata.put("tableId", table.id());
                metadata.put("rowId", row.id());
                metadata.put("rowIndex", row.ordinal());
                if (row.page() != null) {
                    metadata.put("pageNumber", row.page());
                }
                if (table.pageFrom() != null) {
                    metadata.put("tablePageFrom", table.pageFrom());
                }
                if (table.pageTo() != null) {
                    metadata.put("tablePageTo", table.pageTo());
                }
                chunks.add(new TextChunk(
                        document.documentId() + "-t-" + table.id() + "-r-" + row.ordinal(),
                        document.documentId(),
                        content,
                        position++,
                        metadata));
            }
        }
        return chunks;
    }

    private String fallback(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
