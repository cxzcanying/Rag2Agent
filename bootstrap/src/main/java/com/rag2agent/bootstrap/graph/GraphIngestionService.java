package com.rag2agent.bootstrap.graph;

import com.rag2agent.bootstrap.config.Neo4jGraphProperties;
import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.rag.core.document.StructuredDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 图入库编排；图服务不可用时记录降级，不阻断文档检索入库。 */
@Service
public class GraphIngestionService {

    private static final Logger log = LoggerFactory.getLogger(GraphIngestionService.class);

    private final Neo4jGraphProperties properties;
    private final GraphExtractionService extractionService;
    private final KnowledgeGraphRepository repository;

    public GraphIngestionService(
            Neo4jGraphProperties properties,
            GraphExtractionService extractionService,
            KnowledgeGraphRepository repository) {
        this.properties = properties;
        this.extractionService = extractionService;
        this.repository = repository;
    }

    public boolean index(DocumentMeta document, StructuredDocument structured, int version) {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            GraphExtractionService.ExtractionResult extracted = extractionService.extract(structured);
            repository.replaceDocument(
                    document.getId(),
                    document.getKbId(),
                    version,
                    extracted.entities(),
                    extracted.relations());
            return true;
        } catch (RuntimeException exception) {
            log.warn("图谱入库失败，保留文本索引: documentId={}, reason={}",
                    document.getId(), exception.getMessage());
            return false;
        }
    }
}
