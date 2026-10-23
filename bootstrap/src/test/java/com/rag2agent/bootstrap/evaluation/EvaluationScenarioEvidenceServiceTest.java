package com.rag2agent.bootstrap.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.bootstrap.mapper.DocumentMetaMapper;
import com.rag2agent.bootstrap.service.DocumentStructureRepository;
import com.rag2agent.bootstrap.service.DocumentStructureRepository.RowRecord;
import com.rag2agent.bootstrap.service.DocumentStructureRepository.TableRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EvaluationScenarioEvidenceServiceTest {

    @Test
    void tableScenarioReadsExpectedRowsFromStructureStore() {
        DocumentMetaMapper documents = mock(DocumentMetaMapper.class);
        DocumentStructureRepository structure = mock(DocumentStructureRepository.class);
        DocumentMeta document = new DocumentMeta();
        document.setId(9L);
        document.setKbId(3L);
        document.setVersion(2);
        when(documents.selectById(9L)).thenReturn(document);
        when(structure.findTable(9L, 2, "table-1")).thenReturn(Optional.of(
                new TableRecord("table-1", "营收", List.of("区域", "金额"), 1, 1, null)));
        when(structure.findRowsByIds(anyLong(), anyInt(), anyString(), anyList()))
                .thenReturn(List.of(new RowRecord(
                        "row-17", 17, List.of("华东", "100"),
                        "区域=华东; 金额=100", 1, null)));
        var service = new EvaluationScenarioEvidenceService(documents, structure);
        var evalCase = new EvaluationRepository.EvalCase(
                1L, 3L, "华东营收", "100", List.of(9L), "TABLE",
                Map.of("tableId", "table-1", "rowIds", List.of("row-17"),
                        "headers", List.of("区域", "金额")));

        var evidence = service.augment(evalCase, List.of());

        assertEquals(1, evidence.size());
        assertEquals(List.of("row-17"), evidence.getFirst().metadata().get("rowIds"));
        assertEquals("table", evidence.getFirst().metadata().get("contentType"));
    }
}
