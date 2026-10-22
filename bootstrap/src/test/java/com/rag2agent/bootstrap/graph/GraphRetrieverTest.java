package com.rag2agent.bootstrap.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rag2agent.bootstrap.config.Neo4jGraphProperties;
import com.rag2agent.bootstrap.graph.KnowledgeGraphRepository.GraphEvidence;
import com.rag2agent.bootstrap.graph.KnowledgeGraphRepository.GraphPath;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphRetrieverTest {

    @Test
    void mapsPathAndEvidenceToRetrievalCandidate() {
        Neo4jGraphProperties properties = new Neo4jGraphProperties();
        properties.setEnabled(true);
        KnowledgeGraphRepository repository = mock(KnowledgeGraphRepository.class);
        GraphEvidence evidence =
                new GraphEvidence(9L, 2, "texts-3", "USES", 0.8);
        when(repository.searchPaths(3L, "RAG2Agent", 5)).thenReturn(List.of(
                new GraphPath(
                        "RAG2Agent",
                        List.of("USES"),
                        "PostgreSQL",
                        List.of("RAG2Agent", "PostgreSQL"),
                        0.8,
                        List.of(evidence))));

        List<RetrievalResult> results =
                new GraphRetriever(properties, repository, new SimpleMeterRegistry())
                        .search(3L, "RAG2Agent", 5);

        assertEquals(1, results.size());
        assertEquals("graph", results.getFirst().metadata().get("retrievalSource"));
        assertEquals(9L, results.getFirst().metadata().get("documentId"));
        assertEquals("texts-3", results.getFirst().metadata().get("elementId"));
        assertTrue(results.getFirst().content().contains("RAG2Agent -[USES]-> PostgreSQL"));
        assertTrue(results.getFirst().chunkId().startsWith("graph:"));
    }

    @Test
    void disabledOrFailingGraphReturnsNoCandidates() {
        Neo4jGraphProperties properties = new Neo4jGraphProperties();
        KnowledgeGraphRepository repository = mock(KnowledgeGraphRepository.class);
        GraphRetriever retriever =
                new GraphRetriever(properties, repository, new SimpleMeterRegistry());

        assertTrue(retriever.search(3L, "RAG2Agent", 5).isEmpty());
        verifyNoInteractions(repository);

        properties.setEnabled(true);
        when(repository.searchPaths(3L, "RAG2Agent", 5))
                .thenThrow(new IllegalStateException("neo4j unavailable"));
        assertTrue(retriever.search(3L, "RAG2Agent", 5).isEmpty());
    }
}
