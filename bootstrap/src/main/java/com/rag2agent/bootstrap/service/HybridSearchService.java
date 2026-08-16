package com.rag2agent.bootstrap.service;

import com.rag2agent.infra.ai.client.EmbeddingClient;
import com.rag2agent.infra.ai.client.RerankClient;
import com.rag2agent.infra.ai.model.EmbeddingRequest;
import com.rag2agent.infra.ai.model.EmbeddingResponse;
import com.rag2agent.infra.ai.model.RerankRequest;
import com.rag2agent.infra.ai.model.RerankResponse;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import com.rag2agent.rag.core.retrieval.impl.QueryRouter;
import com.rag2agent.rag.core.retrieval.impl.QueryRouter.Route;
import com.rag2agent.rag.core.retrieval.impl.RrfFusion;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 混合检索：向量（pgvector 余弦相似度）+ 关键词（pg_trgm）两路召回，RRF 融合，再 Rerank 精排。
 * 引用溯源：metadata 里带 document_id / chunk_index，检索结果可直接定位原文。
 * 权限边界：SQL 按 kb_id 过滤；版本正确性靠 c.version = d.version 保证只查当前版本。
 */
@Service
public class HybridSearchService {

    private final JdbcTemplate jdbc;
    private final EmbeddingClient embeddingClient;
    private final RerankClient rerankClient;

    public HybridSearchService(JdbcTemplate jdbc, EmbeddingClient embeddingClient, RerankClient rerankClient) {
        this.jdbc = jdbc;
        this.embeddingClient = embeddingClient;
        this.rerankClient = rerankClient;
    }

    public List<RetrievalResult> search(Long kbId, String query, int topK) {
        // 规则路由：疑问句走语义（只向量），短词/编号走关键词（省一次 embedding），其余混合
        Route route = QueryRouter.route(query);
        List<RetrievalResult> vectorResults = (route != Route.KEYWORD)
                ? vectorSearch(kbId, embedQuery(query), topK)
                : List.of();
        List<RetrievalResult> keywordResults = (route != Route.SEMANTIC)
                ? keywordSearch(kbId, query, topK)
                : List.of();

        // RRF 融合两路结果，分数域统一、同名 chunk 去重累加
        List<RetrievalResult> fused = RrfFusion.fuse(List.of(vectorResults, keywordResults), topK);
        if (fused.isEmpty()) {
            return List.of();
        }
        // 精排：用 cross-encoder 对融合后的候选重新打分，取 topK
        return rerank(query, fused, topK);
    }

    private List<Float> embedQuery(String query) {
        EmbeddingResponse response =
                embeddingClient.embed(new EmbeddingRequest("siliconflow", null, List.of(query)));
        if (response.vectors().isEmpty()) {
            throw new IllegalStateException("查询向量化返回为空");
        }
        return response.vectors().get(0);
    }

    private List<RetrievalResult> vectorSearch(Long kbId, List<Float> vector, int topK) {
        String vectorLiteral = toVectorString(vector);
        return jdbc.query(
                """
                SELECT c.id, c.content, c.document_id, c.chunk_index,
                       1 - (c.embedding <=> CAST(? AS vector)) AS score
                FROM document_chunk c
                JOIN document d ON d.id = c.document_id
                WHERE c.kb_id = ? AND c.version = d.version AND c.embedding IS NOT NULL
                ORDER BY c.embedding <=> CAST(? AS vector)
                LIMIT ?
                """,
                (rs, rowNum) -> new RetrievalResult(
                        rs.getString("id"),
                        rs.getString("content"),
                        rs.getDouble("score"),
                        Map.of(
                                "documentId", rs.getLong("document_id"),
                                "chunkIndex", rs.getInt("chunk_index"))),
                vectorLiteral, kbId, vectorLiteral, topK);
    }

    private List<RetrievalResult> keywordSearch(Long kbId, String query, int topK) {
        return jdbc.query(
                """
                SELECT c.id, c.content, c.document_id, c.chunk_index,
                       similarity(c.content, ?) AS score
                FROM document_chunk c
                JOIN document d ON d.id = c.document_id
                WHERE c.kb_id = ? AND c.version = d.version
                  AND (c.content ILIKE '%' || ? || '%' OR similarity(c.content, ?) > 0.1)
                ORDER BY score DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new RetrievalResult(
                        rs.getString("id"),
                        rs.getString("content"),
                        rs.getDouble("score"),
                        Map.of(
                                "documentId", rs.getLong("document_id"),
                                "chunkIndex", rs.getInt("chunk_index"))),
                query, kbId, query, query, topK);
    }

    private List<RetrievalResult> rerank(String query, List<RetrievalResult> fused, int topK) {
        List<String> contents = fused.stream().map(RetrievalResult::content).toList();
        RerankResponse response = rerankClient.rerank(
                new RerankRequest("siliconflow", null, query, contents, Math.min(topK, contents.size())));
        return response.results().stream()
                .map(result -> {
                    RetrievalResult original = fused.get(result.index());
                    return new RetrievalResult(
                            original.chunkId(), original.content(), result.score(), original.metadata());
                })
                .toList();
    }

    private static String toVectorString(List<Float> vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(vector.get(i));
        }
        return sb.append("]").toString();
    }
}
