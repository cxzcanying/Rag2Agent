package com.rag2agent.bootstrap.service;

public record SearchOptions(
        Strategy strategy,
        int topK,
        int candidateTopK,
        double rrfK,
        boolean rerankEnabled,
        Double rerankMinScore) {

    public enum Strategy {
        AUTO,
        VECTOR,
        KEYWORD,
        HYBRID
    }

    public SearchOptions {
        strategy = strategy == null ? Strategy.AUTO : strategy;
        if (topK < 1 || topK > 100) {
            throw new IllegalArgumentException("topK 必须在 1~100 之间");
        }
        if (candidateTopK < topK || candidateTopK > 200) {
            throw new IllegalArgumentException("candidateTopK 必须在 topK~200 之间");
        }
        if (rrfK < 0 || rrfK > 1000) {
            throw new IllegalArgumentException("rrfK 必须在 0~1000 之间");
        }
        if (rerankMinScore != null && (rerankMinScore < 0 || rerankMinScore > 1)) {
            throw new IllegalArgumentException("rerankMinScore 必须在 0~1 之间");
        }
    }

    public static SearchOptions defaults(int topK) {
        return new SearchOptions(Strategy.AUTO, topK, topK, 60.0, true, null);
    }
}
