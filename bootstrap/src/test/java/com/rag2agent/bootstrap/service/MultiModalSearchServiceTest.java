package com.rag2agent.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rag2agent.infra.ai.client.DocumentIntelligenceClient;
import com.rag2agent.infra.ai.client.VisionClient;
import com.rag2agent.infra.ai.config.DoclingProperties;
import com.rag2agent.infra.ai.exception.AiClientException;
import com.rag2agent.infra.ai.model.DocumentIntelligenceResult;
import com.rag2agent.infra.ai.model.VisionRequest;
import com.rag2agent.infra.ai.model.VisionResult;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MultiModalSearchServiceTest {

    @Test
    void usesVisionDescriptionAsRetrievalQuery() {
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        HybridSearchService search = mock(HybridSearchService.class);
        VisionClient vision = mock(VisionClient.class);
        when(vision.available()).thenReturn(true);
        when(vision.describe(any(VisionRequest.class)))
                .thenReturn(new VisionResult("柱状图 显示 营收 增长", "qwen", "qwen3-vl"));
        String effective = "图表说明了什么\n柱状图 显示 营收 增长";
        when(search.search(3L, effective, 5)).thenReturn(List.of(
                new RetrievalResult("c1", "营收增长", 0.9,
                        Map.of("documentId", 1, "chunkIndex", 0))));

        var result = new MultiModalSearchService(
                kb, search, vision, mock(DocumentIntelligenceClient.class), new DoclingProperties()).search(
                2L, 3L, "图表说明了什么", new byte[] {1}, "image/png", 5);

        assertTrue(result.imageUsed());
        assertEquals(1, result.results().size());
        assertTrue(result.effectiveQuery().contains("营收"));
    }

    @Test
    void fallsBackToDoclingOcrWhenVisionProviderRejectsRequest() {
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        HybridSearchService search = mock(HybridSearchService.class);
        VisionClient vision = mock(VisionClient.class);
        when(vision.available()).thenReturn(true);
        when(vision.describe(any(VisionRequest.class)))
                .thenThrow(new AiClientException("missing key"));
        DocumentIntelligenceClient documentClient = mock(DocumentIntelligenceClient.class);
        when(documentClient.convert(any(), any(), any())).thenReturn(
                new DocumentIntelligenceResult(
                        "success", 0, new ObjectMapper().createObjectNode(), "## 化学键", ""));
        DoclingProperties docling = new DoclingProperties();
        docling.setEnabled(true);
        when(search.search(3L, "图中写了什么\n## 化学键", 5)).thenReturn(List.of());

        var result = new MultiModalSearchService(
                kb, search, vision, documentClient, docling).search(
                2L, 3L, "图中写了什么", new byte[] {1}, "image/png", 5);

        assertTrue(result.effectiveQuery().contains("化学键"));
    }
}
