package com.rag2agent.rag.core.split.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rag2agent.rag.core.document.StructuredDocument;
import com.rag2agent.rag.core.document.StructuredDocument.Row;
import com.rag2agent.rag.core.document.StructuredDocument.Table;
import com.rag2agent.rag.core.split.TextChunk;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TableChunkBuilderTest {

    @Test
    void includesCaptionHeadersRowAndPageInEveryChunk() {
        Table table = new Table(
                "tables-0",
                "收入表",
                List.of("项目", "金额"),
                List.of(new Row("tables-0-row-1", 1, List.of("收入", "100"), "项目=收入；金额=100", 3, null)),
                2,
                3,
                null,
                "");
        StructuredDocument document = new StructuredDocument(
                "doc-1", "", "", List.of(), List.of(), List.of(table), List.of(), Map.of());

        List<TextChunk> chunks = new TableChunkBuilder().build(document, 10);

        assertEquals(1, chunks.size());
        assertTrue(chunks.getFirst().content().contains("收入表"));
        assertTrue(chunks.getFirst().content().contains("项目 | 金额"));
        assertTrue(chunks.getFirst().content().contains("页码：3"));
        assertEquals("tables-0", chunks.getFirst().metadata().get("tableId"));
        assertEquals(3, chunks.getFirst().metadata().get("pageNumber"));
        assertEquals(10, chunks.getFirst().position());
    }
}
