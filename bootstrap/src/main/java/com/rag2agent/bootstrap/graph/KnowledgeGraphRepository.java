package com.rag2agent.bootstrap.graph;

import com.rag2agent.bootstrap.config.Neo4jGraphProperties;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeGraphRepository {

    private final Driver driver;
    private final Neo4jGraphProperties properties;

    public KnowledgeGraphRepository(Driver driver, Neo4jGraphProperties properties) {
        this.driver = driver;
        this.properties = properties;
    }

    public void replaceDocument(
            Long documentId,
            Long kbId,
            int version,
            List<GraphEntity> entities,
            List<GraphRelation> relations) {
        try (var session = session()) {
            session.executeWriteWithoutResult(tx -> {
                tx.run("""
                        MATCH ()-[r:RELATED {document_id: $documentId, kb_id: $kbId}]->()
                        DELETE r
                        """, Map.of("documentId", documentId, "kbId", kbId));
                tx.run("""
                        MATCH (d:Document {document_id: $documentId, kb_id: $kbId})
                        DETACH DELETE d
                        """, Map.of("documentId", documentId, "kbId", kbId));
                tx.run("""
                        MERGE (d:Document {document_id: $documentId, kb_id: $kbId})
                        SET d.version = $version, d.updated_at = timestamp()
                        """, Map.of("documentId", documentId, "kbId", kbId, "version", version));
                if (!entities.isEmpty()) {
                    tx.run("""
                            UNWIND $items AS item
                            MERGE (e:Entity {kb_id: $kbId, canonical_name: item.name})
                            SET e.entity_type = item.type,
                                e.version = $version,
                                e.confidence = item.confidence,
                                e.updated_at = timestamp()
                            WITH e, item
                            MATCH (d:Document {document_id: $documentId, kb_id: $kbId})
                            MERGE (e)-[m:MENTIONS {
                                document_id: $documentId,
                                version: $version,
                                element_id: item.evidence
                            }]->(d)
                            SET m.confidence = item.confidence
                            """, Map.of(
                            "items", entities.stream().map(GraphEntity::asMap).toList(),
                            "kbId", kbId,
                            "documentId", documentId,
                            "version", version));
                }
                if (!relations.isEmpty()) {
                    tx.run("""
                            UNWIND $items AS item
                            MATCH (source:Entity {kb_id: $kbId, canonical_name: item.source})
                            MATCH (target:Entity {kb_id: $kbId, canonical_name: item.target})
                            MERGE (source)-[r:RELATED {
                                kb_id: $kbId,
                                relation_type: item.type,
                                document_id: $documentId,
                                version: $version,
                                evidence: item.evidence
                            }]->(target)
                            SET r.confidence = item.confidence,
                                r.updated_at = timestamp()
                            """, Map.of(
                            "items", relations.stream().map(GraphRelation::asMap).toList(),
                            "kbId", kbId,
                            "documentId", documentId,
                            "version", version));
                }
            });
        }
    }

    public List<GraphPath> searchPaths(Long kbId, String query, int limit) {
        int boundedLimit = Math.min(Math.max(limit, 1), properties.getMaxResults());
        int maxHops = Math.min(Math.max(properties.getMaxHops(), 1), 3);
        String cypher = """
                MATCH (start:Entity {kb_id: $kbId})
                WHERE toLower(start.canonical_name) CONTAINS toLower($query)
                MATCH p=(start)-[rels:RELATED*1..%d]-(end:Entity {kb_id: $kbId})
                WHERE all(r IN rels WHERE r.kb_id = $kbId)
                RETURN start.canonical_name AS source,
                       [r IN rels | r.relation_type] AS relationTypes,
                       end.canonical_name AS target,
                       [n IN nodes(p) | n.canonical_name] AS nodes,
                       reduce(score = 0.0, r IN rels | score + coalesce(r.confidence, 0.0)) AS score
                ORDER BY score DESC
                LIMIT $limit
                """.formatted(maxHops);
        try (var session = session()) {
            return session.executeRead(tx -> tx.run(cypher, Map.of(
                            "kbId", kbId, "query", query, "limit", boundedLimit))
                    .stream()
                    .map(record -> new GraphPath(
                            record.get("source").asString(),
                            record.get("relationTypes").asList(value -> value.asString()),
                            record.get("target").asString(),
                            record.get("nodes").asList(value -> value.asString()),
                            record.get("score").asDouble()))
                    .toList());
        }
    }

    public void deleteDocument(Long documentId, Long kbId) {
        try (var session = session()) {
            session.executeWriteWithoutResult(tx -> {
                tx.run("""
                        MATCH ()-[r:RELATED {document_id: $documentId, kb_id: $kbId}]->()
                        DELETE r
                        """, Map.of("documentId", documentId, "kbId", kbId));
                tx.run("""
                        MATCH (d:Document {document_id: $documentId, kb_id: $kbId})
                        DETACH DELETE d
                        """, Map.of("documentId", documentId, "kbId", kbId));
            });
        }
    }

    private org.neo4j.driver.Session session() {
        return driver.session(SessionConfig.builder()
                .withDatabase(properties.getDatabase())
                .build());
    }

    public record GraphEntity(
            String name, String type, String evidence, double confidence) {
        Map<String, Object> asMap() {
            return Map.of(
                    "name", name,
                    "type", type,
                    "evidence", evidence,
                    "confidence", Math.clamp(confidence, 0.0, 1.0));
        }
    }

    public record GraphRelation(
            String source, String target, String type, String evidence, double confidence) {
        Map<String, Object> asMap() {
            return Map.of(
                    "source", source,
                    "target", target,
                    "type", type,
                    "evidence", evidence,
                    "confidence", Math.clamp(confidence, 0.0, 1.0));
        }
    }

    public record GraphPath(
            String source,
            List<String> relationTypes,
            String target,
            List<String> nodes,
            double score) {}
}
