package com.rag2agent.rag.core.retrieval.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RrfFusionTest {

    private static RetrievalResult r(String id) {
        return new RetrievalResult(id, "content-" + id, 0.0, Map.of());
    }

    @Test
    void fuse_deduplicatesAcrossLists() {
        List<RetrievalResult> vector = List.of(r("a"), r("b"), r("c"));
        List<RetrievalResult> keyword = List.of(r("c"), r("b"), r("d"));

        List<RetrievalResult> fused = RrfFusion.fuse(List.of(vector, keyword), 10);

        // c 在两路都排前列，RRF 分数应最高
        assertEquals("c", fused.get(0).chunkId());
        assertEquals(4, fused.size());
    }

    @Test
    void fuse_respectsTopK() {
        List<RetrievalResult> single = List.of(r("a"), r("b"), r("c"), r("d"), r("e"));
        List<RetrievalResult> fused = RrfFusion.fuse(List.of(single), 3);
        assertEquals(3, fused.size());
    }

    @Test
    void fuse_higherRankGetsHigherScore() {
        List<RetrievalResult> ranked = List.of(r("first"), r("second"), r("third"));
        List<RetrievalResult> fused = RrfFusion.fuse(List.of(ranked), 10);
        assertTrue(fused.get(0).score() > fused.get(1).score());
        assertTrue(fused.get(1).score() > fused.get(2).score());
    }

    @Test
    void fuse_acceptsEvaluationK() {
        List<List<RetrievalResult>> lists = List.of(List.of(r("a"), r("b")));

        List<RetrievalResult> lowK = RrfFusion.fuse(lists, 2, 0);
        List<RetrievalResult> highK = RrfFusion.fuse(lists, 2, 100);

        assertTrue(lowK.get(0).score() - lowK.get(1).score()
                > highK.get(0).score() - highK.get(1).score());
    }
}
