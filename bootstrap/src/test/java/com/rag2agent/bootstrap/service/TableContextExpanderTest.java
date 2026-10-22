package com.rag2agent.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rag2agent.bootstrap.service.DocumentStructureRepository.RowRecord;
import com.rag2agent.bootstrap.service.DocumentStructureRepository.TableRecord;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TableContextExpanderTest {

    @Test
    void addsHeadersAndNeighborRowsToExpandedContent() {
        DocumentStructureRepository repository = mock(DocumentStructureRepository.class);
        when(repository.findTable(7L, 2, "tables-0"))
                .thenReturn(Optional.of(new TableRecord(
                        "tables-0", "收入表", List.of("项目", "金额"), 2, 3, null)));
        when(repository.findRowsAround(7L, 2, "tables-0", 2, 2)).thenReturn(List.of(
                new RowRecord("r1", 1, List.of(), "项目=成本；金额=60", 2, null),
                new RowRecord("r2", 2, List.of(), "项目=利润；金额=100", 3, null),
                new RowRecord("r3", 3, List.of(), "项目=税费；金额=10", 3, null)));
        RetrievalResult result = new RetrievalResult(
                "chunk-1",
                "命中行",
                0.9,
                Map.of(
                        "contentType", "table_row",
                        "documentId", 7,
                        "version", 2,
                        "tableId", "tables-0",
                        "rowIndex", 2));

        RetrievalResult expanded = new TableContextExpander(repository).expand(result);

        String content = (String) expanded.metadata().get("expandedContent");
        assertTrue(content.contains("项目 | 金额"));
        assertTrue(content.contains("项目=成本"));
        assertTrue(content.contains("[命中]"));
        assertEquals(3, expanded.metadata().get("neighborRowCount"));
    }
}
