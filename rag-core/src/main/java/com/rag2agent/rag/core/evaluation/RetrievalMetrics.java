package com.rag2agent.rag.core.evaluation;

import java.util.List;

/**
 * 检索评测聚合指标。firstRelevantRanks 使用 1-based 排名，0 表示未命中。
 */
public record RetrievalMetrics(int totalCases, int hitCases, double hitAtK, double mrr) {

    public static RetrievalMetrics calculate(List<Integer> firstRelevantRanks) {
        if (firstRelevantRanks == null || firstRelevantRanks.isEmpty()) {
            return new RetrievalMetrics(0, 0, 0.0, 0.0);
        }
        int hits = 0;
        double reciprocalRankSum = 0.0;
        for (Integer rank : firstRelevantRanks) {
            if (rank != null && rank > 0) {
                hits++;
                reciprocalRankSum += 1.0 / rank;
            }
        }
        int total = firstRelevantRanks.size();
        return new RetrievalMetrics(total, hits, (double) hits / total, reciprocalRankSum / total);
    }
}
