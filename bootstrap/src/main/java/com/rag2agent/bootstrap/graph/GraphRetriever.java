package com.rag2agent.bootstrap.graph;

import com.rag2agent.bootstrap.config.Neo4jGraphProperties;
import com.rag2agent.bootstrap.graph.KnowledgeGraphRepository.GraphEvidence;
import com.rag2agent.bootstrap.graph.KnowledgeGraphRepository.GraphPath;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 把图路径转换成普通检索候选，使图、向量和关键词可以共用 RRF 与重排链路。 */
@Service
public class GraphRetriever {

    private static final Logger log = LoggerFactory.getLogger(GraphRetriever.class);

    private final Neo4jGraphProperties properties;
    private final KnowledgeGraphRepository repository;
    private final MeterRegistry meterRegistry;

    public GraphRetriever(
            Neo4jGraphProperties properties,
            KnowledgeGraphRepository repository,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public List<RetrievalResult> search(Long kbId, String query, int limit) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        try {
            List<RetrievalResult> results = repository.searchPaths(kbId, query, limit).stream()
                    .map(path -> toResult(kbId, path))
                    .toList();
            meterRegistry.counter("rag2agent.search.graph.results").increment(results.size());
            return results;
        } catch (RuntimeException exception) {
            meterRegistry.counter("rag2agent.search.graph.fallback", "reason", "error").increment();
            log.warn("图检索失败，降级为文本检索: kbId={}, reason={}", kbId, exception.getMessage());
            return List.of();
        }
    }

    private RetrievalResult toResult(Long kbId, GraphPath path) {
        String chunkId = "graph:" + UUID.nameUUIDFromBytes(
                (kbId + ":" + path.source() + ":" + path.relationTypes()
                        + ":" + path.target() + ":" + path.evidence())
                        .getBytes(StandardCharsets.UTF_8));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("retrievalSource", "graph");
        metadata.put("contentType", "knowledge_graph_path");
        metadata.put("graphSource", path.source());
        metadata.put("graphTarget", path.target());
        metadata.put("graphNodes", path.nodes());
        metadata.put("relationTypes", path.relationTypes());
        metadata.put("graphEvidence", path.evidence());
        GraphEvidence firstEvidence = path.evidence().stream()
                .filter(evidence -> evidence.documentId() != null)
                .findFirst()
                .orElse(null);
        if (firstEvidence != null) {
            metadata.put("documentId", firstEvidence.documentId());
            metadata.put("version", firstEvidence.version());
            metadata.put("elementId", firstEvidence.elementId());
        }
        double normalizedScore = path.evidence().isEmpty()
                ? path.score()
                : Math.min(1.0, path.score() / path.evidence().size());
        return new RetrievalResult(
                chunkId, render(path), normalizedScore, Map.copyOf(metadata));
    }

    private String render(GraphPath path) {
        StringBuilder text = new StringBuilder("知识图谱路径：");
        for (int index = 0; index < path.nodes().size(); index++) {
            text.append(path.nodes().get(index));
            if (index < path.relationTypes().size()) {
                text.append(" -[").append(path.relationTypes().get(index)).append("]-> ");
            }
        }
        if (!path.evidence().isEmpty()) {
            text.append("；证据元素：");
            for (int index = 0; index < path.evidence().size(); index++) {
                if (index > 0) {
                    text.append("、");
                }
                GraphEvidence evidence = path.evidence().get(index);
                text.append(evidence.elementId());
            }
        }
        return text.toString();
    }
}
