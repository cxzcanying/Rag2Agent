package com.rag2agent.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.EmbeddingCacheProperties;
import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.bootstrap.mapper.DocumentChunkMapper;
import com.rag2agent.bootstrap.storage.MinioStorageService;
import com.rag2agent.infra.ai.client.EmbeddingClient;
import com.rag2agent.infra.ai.model.EmbeddingResponse;
import com.rag2agent.rag.core.split.TextChunk;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class IngestPipelineServiceTest {

    @Test
    void batchFailureStopsAtFailedBatchWithoutPartialSecondInsert() throws Exception {
        DocumentChunkMapper chunks = mock(DocumentChunkMapper.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.providerName()).thenReturn("test");
        when(embedding.modelName()).thenReturn("model");
        when(embedding.embed(any())).thenReturn(vectors(16)).thenThrow(new IllegalStateException("upstream"));
        QueryEmbeddingCache cache = new QueryEmbeddingCache(
                null, new ObjectMapper(), new EmbeddingCacheProperties(), new SimpleMeterRegistry());
        IngestPipelineService service = new IngestPipelineService(
                mock(com.rag2agent.bootstrap.mapper.DocumentMetaMapper.class), chunks,
                mock(MinioStorageService.class), embedding, mock(IngestTaskService.class),
                mock(PlatformTransactionManager.class), mock(StringRedisTemplate.class), cache);

        DocumentMeta document = new DocumentMeta();
        document.setId(1L);
        document.setKbId(2L);
        List<TextChunk> source = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            source.add(new TextChunk("id" + i, "1", "chunk-" + i, i, java.util.Map.of()));
        }
        Method persist = IngestPipelineService.class.getDeclaredMethod(
                "persistChunks", DocumentMeta.class, List.class, int.class);
        persist.setAccessible(true);

        assertThrows(Exception.class, () -> persist.invoke(service, document, source, 1));
        org.mockito.Mockito.verify(chunks, org.mockito.Mockito.times(1)).insertChunks(any());
    }

    @Test
    void lockLeaseLossAndReleaseErrorsAreCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IngestPipelineService service = new IngestPipelineService(
                mock(com.rag2agent.bootstrap.mapper.DocumentMetaMapper.class),
                mock(DocumentChunkMapper.class), mock(MinioStorageService.class),
                mock(EmbeddingClient.class), mock(IngestTaskService.class),
                mock(PlatformTransactionManager.class), mock(StringRedisTemplate.class), registry,
                mock(QueryEmbeddingCache.class));
        service.recordLeaseRenewal(0L);
        service.recordLeaseRenewalError();
        service.recordLeaseReleaseError();
        assertEquals(1.0, registry.get("rag2agent.lock.operations")
                .tag("lock", "ingest-document").tag("operation", "renew")
                .tag("outcome", "lost").counter().count());
        assertEquals(1.0, registry.get("rag2agent.lock.operations")
                .tag("lock", "ingest-document").tag("operation", "release")
                .tag("outcome", "error").counter().count());
    }

    private static EmbeddingResponse vectors(int count) {
        List<List<Float>> vectors = new ArrayList<>();
        for (int i = 0; i < count; i++) vectors.add(List.of(1.0f, 2.0f));
        return new EmbeddingResponse(vectors);
    }
}
