package com.rag2agent.bootstrap.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DocumentChunkMapperTest {

    @Test
    void emptyBatchIsANoOp() {
        AtomicInteger calls = new AtomicInteger();
        DocumentChunkMapper mapper = new DocumentChunkMapper() {
            @Override
            public int insertChunksBatch(List<ChunkRow> chunks) {
                calls.incrementAndGet();
                return chunks.size();
            }

            @Override public int insertChunk(Long documentId, Long kbId, int chunkIndex, String content,
                    int tokenCount, String embedding, Integer pageNumber, String metadata, int version) { return 0; }
            @Override public int deleteByDocumentAndVersion(Long documentId, int version) { return 0; }
            @Override public int deleteBelowVersion(Long documentId, int version) { return 0; }
            @Override public int deleteByDocument(Long documentId) { return 0; }
        };

        assertEquals(0, mapper.insertChunks(List.of()));
        assertEquals(0, mapper.insertChunks(null));
        assertEquals(0, calls.get());
    }
}
