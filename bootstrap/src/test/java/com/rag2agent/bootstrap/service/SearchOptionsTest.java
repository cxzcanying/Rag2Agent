package com.rag2agent.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SearchOptionsTest {

    @Test
    void defaultsKeepTheProductionSearchShape() {
        SearchOptions options = SearchOptions.defaults(5);

        assertEquals(SearchOptions.Strategy.AUTO, options.strategy());
        assertEquals(5, options.topK());
        assertEquals(5, options.candidateTopK());
        assertEquals(60.0, options.rrfK());
        assertEquals(true, options.rerankEnabled());
    }

    @Test
    void rejectsCandidatePoolSmallerThanTopK() {
        assertThrows(IllegalArgumentException.class, () ->
                new SearchOptions(SearchOptions.Strategy.HYBRID, 10, 5, 60, true, null));
    }
}
