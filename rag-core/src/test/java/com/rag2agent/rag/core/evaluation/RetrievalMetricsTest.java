package com.rag2agent.rag.core.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalMetricsTest {

    @Test
    void calculate_usesMissesInMetricDenominator() {
        RetrievalMetrics metrics = RetrievalMetrics.calculate(List.of(1, 2, 0));

        assertEquals(3, metrics.totalCases());
        assertEquals(2, metrics.hitCases());
        assertEquals(2.0 / 3.0, metrics.hitAtK(), 1e-9);
        assertEquals(0.5, metrics.mrr(), 1e-9);
    }

    @Test
    void calculate_handlesEmptyDataset() {
        RetrievalMetrics metrics = RetrievalMetrics.calculate(List.of());

        assertEquals(0, metrics.totalCases());
        assertEquals(0.0, metrics.hitAtK());
        assertEquals(0.0, metrics.mrr());
    }
}
