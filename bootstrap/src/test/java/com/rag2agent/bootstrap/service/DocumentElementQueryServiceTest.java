package com.rag2agent.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.bootstrap.service.DocumentStructureRepository.ElementRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentElementQueryServiceTest {

    @Test
    void returnsStableElementCursorAndPageRange() {
        DocumentService documents = mock(DocumentService.class);
        DocumentStructureRepository repository = mock(DocumentStructureRepository.class);
        DocumentMeta document = new DocumentMeta();
        document.setId(7L);
        document.setVersion(2);
        when(documents.requireOwnedDocument(3L, 7L)).thenReturn(document);
        when(repository.findElements(7L, 2, -1, 3)).thenReturn(List.of(
                new ElementRecord("texts-0", null, "title", 0, 1, 1, null, "标题"),
                new ElementRecord("tables-0", null, "table", 1, 2, 3, null, "表格"),
                new ElementRecord("pictures-0", null, "picture", 2, 3, 3, null, "图片")));
        when(repository.countElements(7L, 2)).thenReturn(3L);

        var page = new DocumentElementQueryService(documents, repository)
                .page(3L, 7L, null, null, 2);

        assertEquals(2, page.elements().size());
        assertTrue(page.hasMore());
        assertEquals("element:1", page.continuationCursor());
        assertEquals(2, page.elements().getLast().pageFrom());
    }
}
