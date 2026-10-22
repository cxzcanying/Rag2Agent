package com.rag2agent.bootstrap.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.dto.TableDtos.TablePage;
import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.bootstrap.service.DocumentService;
import com.rag2agent.bootstrap.service.TableQueryService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReadTableRowsToolTest {

    @Test
    void returnsHeaderContextAndCursorForAgent() {
        DocumentService documents = mock(DocumentService.class);
        TableQueryService tables = mock(TableQueryService.class);
        DocumentMeta document = new DocumentMeta();
        document.setId(7L);
        when(documents.requireOwnedDocument(2L, 7L)).thenReturn(document);
        when(tables.page(2L, 7L, "tables-0", null, null, 20))
                .thenReturn(new TablePage(
                        "tables-0", "收入表", List.of("项目", "金额"), 2, 3,
                        3, 1, 2, true, "row:2", List.of()));
        ReadTableRowsTool tool = new ReadTableRowsTool(
                documents, tables, new ObjectMapper());

        tool.validateAccess(2L, Map.of("document_id", 7, "table_id", "tables-0"));
        String result = tool.execute(2L, Map.of("document_id", 7, "table_id", "tables-0"));

        assertTrue(result.contains("\"headers\":[\"项目\",\"金额\"]"));
        assertTrue(result.contains("\"continuationCursor\":\"row:2\""));
        assertEquals(
                List.of("document_id", "table_id"),
                (List<?>) tool.descriptor().parameters().get("required"));
    }
}
