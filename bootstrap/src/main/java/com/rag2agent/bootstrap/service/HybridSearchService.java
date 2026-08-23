package com.rag2agent.bootstrap.service;

import com.rag2agent.infra.ai.client.EmbeddingClient;
import com.rag2agent.infra.ai.client.RerankClient;
import com.rag2agent.infra.ai.model.EmbeddingRequest;
import com.rag2agent.infra.ai.model.EmbeddingResponse;
import com.rag2agent.infra.ai.model.RerankRequest;
import com.rag2agent.infra.ai.model.RerankResponse;
import com.rag2agent.infra.ai.model.RerankResult;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import com.rag2agent.rag.core.retrieval.impl.QueryRouter;
import com.rag2agent.rag.core.retrieval.impl.QueryRouter.Route;
import com.rag2agent.rag.core.retrieval.impl.RrfFusion;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 混合检索：向量（pgvector 余弦相似度）+ 关键词（pg_trgm）两路召回，RRF 融合，再 Rerank 精排。
 * 引用溯源：metadata 里带 document_id / chunk_index，检索结果可直接定位原文。
 * 权限边界：SQL 按 kb_id 过滤；版本正确性靠 c.version = d.version 保证只查当前版本。
 * <p>
 * 编排流程：QueryRouter 规则路由决定走哪几路 → 两路召回（向量/关键词）→ RRF 融合
 * （分数域统一、同名 chunk 去重累加）→ bge-reranker 精排（只改排序不改召回）。
 * @author 21311
 */
@Service
public class HybridSearchService {

    private final JdbcTemplate jdbc;
    private final EmbeddingClient embeddingClient;
    private final RerankClient rerankClient;
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;
    private final Executor retrievalTaskExecutor;

    /**
     * 三个依赖的分工：
     * JdbcTemplate 负责两条检索 SQL；EmbeddingClient 把查询文本向量化；
     * RerankClient 对融合后的候选重新打分。
     */
    public HybridSearchService(
            JdbcTemplate jdbc,
            EmbeddingClient embeddingClient,
            RerankClient rerankClient,
            MeterRegistry meterRegistry,
            ObservationRegistry observationRegistry,
            @Qualifier("retrievalTaskExecutor") Executor retrievalTaskExecutor) {
        this.jdbc = jdbc;
        this.embeddingClient = embeddingClient;
        this.rerankClient = rerankClient;
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry;
        this.retrievalTaskExecutor = retrievalTaskExecutor;
    }

    public List<RetrievalResult> search(Long kbId, String query, int topK) {
        return search(kbId, query, SearchOptions.defaults(topK));
    }

    public List<RetrievalResult> search(Long kbId, String query, SearchOptions options) {
        return Observation.createNotStarted("rag2agent.search", observationRegistry)
                .lowCardinalityKeyValue("strategy", metricStrategy(options))
                .observe(() -> measuredSearch(kbId, query, options));
    }

    private List<RetrievalResult> measuredSearch(Long kbId, String query, SearchOptions options) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            List<RetrievalResult> results = doSearch(kbId, query, options);
            DistributionSummary.builder("rag2agent.search.results")
                    .description("每次检索返回的结果数")
                    .tag("strategy", metricStrategy(options))
                    .register(meterRegistry)
                    .record(results.size());
            return results;
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(Timer.builder("rag2agent.search.duration")
                    .description("RAG 检索端到端耗时")
                    .tag("strategy", metricStrategy(options))
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    private List<RetrievalResult> doSearch(Long kbId, String query, SearchOptions options) {
        // 规则路由：疑问句大部分情况是开放式问题走语义（只向量），短词/编号是专业术语走关键词（省一次 embedding），其余混合
        Route route = Observation.createNotStarted("rag2agent.search.route", observationRegistry)
                .lowCardinalityKeyValue("strategy", metricStrategy(options))
                .observe(() -> resolveRoute(options.strategy(), query));
        CompletableFuture<List<RetrievalResult>> vectorFuture = route != Route.KEYWORD
                ? CompletableFuture.supplyAsync(
                        () -> Observation.createNotStarted("rag2agent.search.vector", observationRegistry)
                                .lowCardinalityKeyValue("route", route.name().toLowerCase(Locale.ROOT))
                                .observe(() -> vectorSearch(kbId, embedQuery(query), options.candidateTopK())),
                        retrievalTaskExecutor)
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<RetrievalResult>> keywordFuture = route != Route.SEMANTIC
                ? CompletableFuture.supplyAsync(
                        () -> Observation.createNotStarted("rag2agent.search.keyword", observationRegistry)
                                .lowCardinalityKeyValue("route", route.name().toLowerCase(Locale.ROOT))
                                .observe(() -> keywordSearch(kbId, query, options.candidateTopK())),
                        retrievalTaskExecutor)
                : CompletableFuture.completedFuture(List.of());
        // 两路召回互不依赖，受控并行后再统一等待；任一路失败仍按失败传播，避免静默降低召回质量。
        List<RetrievalResult> vectorResults = vectorFuture.join();
        List<RetrievalResult> keywordResults = keywordFuture.join();

        // RRF 融合两路结果，分数域统一、同名 chunk 去重累加
        int fusedTopK = options.rerankEnabled() ? options.candidateTopK() : options.topK();
        List<RetrievalResult> fused = Observation.createNotStarted("rag2agent.search.rrf", observationRegistry)
                .lowCardinalityKeyValue("route", route.name().toLowerCase(Locale.ROOT))
                .observe(() -> RrfFusion.fuse(
                        List.of(vectorResults, keywordResults), fusedTopK, options.rrfK()));
        // 两路都没有候选：直接返回空，避免拿空列表去调 rerank 白花钱
        if (fused.isEmpty()) {
            return List.of();
        }
        if (!options.rerankEnabled()) {
            return fused.stream().limit(options.topK()).toList();
        }
        // 精排：用 cross-encoder 对融合后的候选重新打分，取 topK
        return Observation.createNotStarted("rag2agent.search.rerank", observationRegistry)
                .lowCardinalityKeyValue("enabled", "true")
                .observe(() -> rerank(query, fused, options.topK())).stream()
                .filter(result -> options.rerankMinScore() == null
                        || result.score() >= options.rerankMinScore())
                .toList();
    }

    private String metricStrategy(SearchOptions options) {
        return options.strategy().name().toLowerCase(Locale.ROOT);
    }

    private Route resolveRoute(SearchOptions.Strategy strategy, String query) {
        return switch (strategy) {
            case AUTO -> QueryRouter.route(query);
            case VECTOR -> Route.SEMANTIC;
            case KEYWORD -> Route.KEYWORD;
            case HYBRID -> Route.HYBRID;
        };
    }

    /**
     * 查询向量化：把用户问题转成 1024 维向量，供 pgvector 余弦检索使用。
     * model 传 null 表示走 provider 配置里的默认模型（BAAI/bge-m3）。
     */
    private List<Float> embedQuery(String query) {
        EmbeddingResponse response = Observation.createNotStarted(
                        "rag2agent.search.embedding", observationRegistry)
                .lowCardinalityKeyValue("provider", "siliconflow")
                .observe(() -> embeddingClient.embed(new EmbeddingRequest(
                        "siliconflow", null, List.of(query))));
        // 向量化失败宁可抛异常，也不返回空结果让上层误以为"没查到"
        if (response.vectors().isEmpty()) {
            throw new IllegalStateException("查询向量化返回为空");
        }
        return response.vectors().getFirst();
    }

    /**
     * 向量检索：按余弦距离（<=>）排序取最相似的 chunk。
     * 三个关键点：
     * 1) 1 - 距离 = 相似度，越大越靠前；
     * 2) JOIN document 且 c.version = d.version，只查每个文档的当前版本；
     * 3) kb_id 过滤是权限边界，只搜指定知识库。
     */
    private List<RetrievalResult> vectorSearch(Long kbId, List<Float> vector, int topK) {
        String vectorLiteral = toVectorString(vector);
        //<=> 是 pgvector 的余弦距离，距离越小越相似
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

    /**
     * 关键词检索：pg_trgm 相似度 + ILIKE 精确包含。
     * ILIKE 抓专有名词/编号/API 名的精确命中，similarity 做模糊兜底，
     * 两个条件 OR 避免输入稍有出入就漏召回；同样带 kb_id 与版本过滤。
     */
    private List<RetrievalResult> keywordSearch(Long kbId, String query, int topK) {
        //ILIKE '%query%'精确包含
        //similarity(content, query) > 0.1：模糊兜底，此方法对于中文基本无效，仅对于漏写字母，大小写错误等能正常识别
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
                query, kbId, escapeLike(query), query, topK);
    }

    /**
     * 精排：把融合后的候选文本交给 cross-encoder 重新打分。
     * 把问题和文档拼成一句话整体输入模型（[CLS] 问题 [SEP] 这段文本），模型直接输出一个 0~1 的相关性分数。
     * 因为模型能看到问题和文本每个词之间的交互（注意力可以跨句子比对），打分比向量相似度准得多。代价是每一对（问题, 文档）都要单独算一次，没法预计算，慢。
     * 返回的 (index, score) 中 index 指向传入的 contents 列表，
     * 回映射时只替换 score，chunkId/content/metadata（引用溯源）原样保留。
     */
    private List<RetrievalResult> rerank(String query, List<RetrievalResult> fused, int topK) {
        List<String> contents = fused.stream().map(RetrievalResult::content).toList();
        // top_n 不能超过候选数，否则 rerank 服务端会报错
        RerankResponse response = rerankClient.rerank(
                new RerankRequest("siliconflow", null, query, contents, Math.min(topK, contents.size())));
        return response.results().stream()
                .filter(result -> result.index() >= 0 && result.index() < fused.size())
                .sorted(Comparator.comparingDouble(RerankResult::score).reversed())
                .map(result -> {
                    RetrievalResult original = fused.get(result.index());
                    return new RetrievalResult(
                            original.chunkId(), original.content(), result.score(), original.metadata());
                })
                .toList();
    }

    /**
     * List<Float> -> "[0.1,0.2,0.3]"，供 SQL 的 CAST(? AS vector) 解析。
     * 不用 List.toString() 是因为它会输出带空格的 "[0.1, 0.2]"，pgvector 严格解析时不兼容。
     */
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

    /**
     * LIKE/ILIKE 模式转义：把反斜杠、% 和 _ 转成字面量，
     * 避免用户输入被当作通配符而改变匹配语义。
     */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
