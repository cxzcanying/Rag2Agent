package com.rag2agent.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rag2agent.bootstrap.dto.TableDtos.TablePage;
import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.bootstrap.service.DocumentStructureRepository.RowRecord;
import com.rag2agent.bootstrap.service.DocumentStructureRepository.TableRecord;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TableQueryServiceTest {

    @Test
    void returnsHeaderContextAndStableCursor() {
        DocumentService documents = mock(DocumentService.class);
        DocumentStructureRepository repository = mock(DocumentStructureRepository.class);
        DocumentMeta document = new DocumentMeta();
        document.setId(7L);
        document.setVersion(3);
        when(documents.requireOwnedDocument(2L, 7L)).thenReturn(document);
        when(repository.findTable(7L, 3, "tables-0"))
                .thenReturn(Optional.of(new TableRecord(
                        "tables-0", "收入表", List.of("项目", "金额"), 2, 3, null)));
        when(repository.findRows(7L, 3, "tables-0", -1, 3)).thenReturn(List.of(
                row("tables-0-row-1", 1, "项目=收入；金额=100", 2),
                row("tables-0-row-2", 2, "项目=成本；金额=60", 3),
                row("tables-0-row-3", 3, "项目=利润；金额=40", 3)));
        when(repository.countRows(7L, 3, "tables-0")).thenReturn(3L);

        TablePage page = new TableQueryService(documents, repository)
                .page(2L, 7L, "tables-0", null, null, 2);

        assertEquals(List.of("项目", "金额"), page.headers());
        assertEquals(2, page.rows().size());
        assertEquals(3, page.pageTo());
        assertTrue(page.hasMore());
        assertEquals("row:2", page.continuationCursor());
        assertFalse(page.rows().getFirst().semanticText().isBlank());
    }

    private static RowRecord row(String id, int index, String semanticText, int page) {
        return new RowRecord(id, index, List.of("v1", "v2"), semanticText, page, null);
    }
}
