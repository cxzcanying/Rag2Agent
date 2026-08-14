package com.rag2agent.bootstrap.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * document_chunk 原生 SQL（embedding 为 pgvector 类型，需 ::vector 转换）。
 */
public interface DocumentChunkMapper {

    @Insert("""
            INSERT INTO document_chunk
                (document_id, kb_id, chunk_index, content, token_count, embedding, page_number, metadata)
            VALUES
                (#{documentId}, #{kbId}, #{chunkIndex}, #{content}, #{tokenCount},
                 #{embedding}::vector, #{pageNumber}, CAST(#{metadata} AS jsonb))
            """)
    int insertChunk(
            @Param("documentId") Long documentId,
            @Param("kbId") Long kbId,
            @Param("chunkIndex") int chunkIndex,
            @Param("content") String content,
            @Param("tokenCount") int tokenCount,
            @Param("embedding") String embedding,
            @Param("pageNumber") Integer pageNumber,
            @Param("metadata") String metadata);
}
