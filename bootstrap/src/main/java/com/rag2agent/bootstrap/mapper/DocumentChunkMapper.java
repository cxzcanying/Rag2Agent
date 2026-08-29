package com.rag2agent.bootstrap.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/**
 * document_chunk 原生 SQL（embedding 为 pgvector 类型，需 ::vector 转换）。
 *
 * <p>为什么不用 MyBatis-Plus 实体自动插入：
 * embedding 列是 pgvector 类型，JDBC 没有对应的标准 setObject 映射，
 * 只能把向量序列化成 "[0.1,0.2,...]" 字符串，再靠 SQL 里的 ::vector 显式转类型。
 *
 * <p>三个方法的分工：
 * <ul>
 *   <li>insertChunk：写入一个带版本的 chunk；</li>
 *   <li>deleteByDocumentAndVersion：入库重试前清理"同文档+同版本"残留；</li>
 *   <li>deleteBelowVersion：版本切换成功后清理所有旧版本 chunk。</li>
 * </ul>
 */
public interface DocumentChunkMapper {

    record ChunkRow(
            Long documentId,
            Long kbId,
            int chunkIndex,
            String content,
            int tokenCount,
            String embedding,
            Integer pageNumber,
            String metadata,
            int version) {}

    @Insert({
        "<script>",
        "INSERT INTO document_chunk",
        "(document_id, kb_id, chunk_index, content, token_count, embedding, page_number, metadata, version)",
        "VALUES",
        "<foreach collection='chunks' item='chunk' separator=','>",
        "(#{chunk.documentId}, #{chunk.kbId}, #{chunk.chunkIndex}, #{chunk.content}, #{chunk.tokenCount},",
        "#{chunk.embedding}::vector, #{chunk.pageNumber}, CAST(#{chunk.metadata} AS jsonb), #{chunk.version})",
        "</foreach>",
        "</script>"
    })
    int insertChunks(@Param("chunks") List<ChunkRow> chunks);

    @Insert("""
            INSERT INTO document_chunk
                (document_id, kb_id, chunk_index, content, token_count, embedding, page_number, metadata, version)
            VALUES
                (#{documentId}, #{kbId}, #{chunkIndex}, #{content}, #{tokenCount},
                 #{embedding}::vector, #{pageNumber}, CAST(#{metadata} AS jsonb), #{version})
            """)
    int insertChunk(
            @Param("documentId") Long documentId,
            @Param("kbId") Long kbId,
            @Param("chunkIndex") int chunkIndex,
            @Param("content") String content,
            @Param("tokenCount") int tokenCount,
            @Param("embedding") String embedding,
            @Param("pageNumber") Integer pageNumber,
            @Param("metadata") String metadata,
            @Param("version") int version);

    /**
     * 清理"同文档 + 同版本"的残留 chunk。
     * 场景：消息重试时上次可能写到一半失败（如第 N 批 embedding 挂了），
     * 表里已有这个版本的半截数据，先删再插保证重试不产生重复/残缺。
     */
    @Delete("DELETE FROM document_chunk WHERE document_id = #{documentId} AND version = #{version}")
    int deleteByDocumentAndVersion(
            @Param("documentId") Long documentId, @Param("version") int version);

    /**
     * 清理所有低于指定版本的 chunk。
     * 在 switchVersion 的事务内调用：新版本写入成功后，把旧版本数据全部删掉，
     * 保证检索永远只命中当前版本，重新入库时旧内容不残留。
     */
    @Delete("DELETE FROM document_chunk WHERE document_id = #{documentId} AND version < #{version}")
    int deleteBelowVersion(
            @Param("documentId") Long documentId, @Param("version") int version);

    /**
     * 删除文档的所有 chunk（不分版本），供 delete_document 工具使用。
     */
    @Delete("DELETE FROM document_chunk WHERE document_id = #{documentId}")
    int deleteByDocument(@Param("documentId") Long documentId);
}
