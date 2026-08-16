package com.rag2agent.rag.core.retrieval.impl;

import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）多路召回融合。
 * 对每一路排序结果，排名第 rank 的文档贡献 1/(k + rank + 1)，k 取 60。
 * 同一 chunk 出现在多路时分数累加，实现去重 + 综合排序。
 */
public final class RrfFusion {

    private static final double K = 60.0;

    private RrfFusion() {}

    public static List<RetrievalResult> fuse(List<List<RetrievalResult>> rankedLists, int topK) {
        Map<String, RetrievalResult> resultById = new LinkedHashMap<>();
        Map<String, Double> rrfScore = new LinkedHashMap<>();
        for (List<RetrievalResult> rankedList : rankedLists) {
            for (int rank = 0; rank < rankedList.size(); rank++) {
                RetrievalResult result = rankedList.get(rank);
                double contribution = 1.0 / (K + rank + 1);
                rrfScore.merge(result.chunkId(), contribution, Double::sum);
                resultById.putIfAbsent(result.chunkId(), result);
            }
        }
        List<RetrievalResult> fused = new ArrayList<>();
        resultById.entrySet().stream()
                .sorted((a, b) -> Double.compare(rrfScore.get(b.getKey()), rrfScore.get(a.getKey())))
                .limit(topK)
                .forEach(entry -> {
                    RetrievalResult original = entry.getValue();
                    fused.add(new RetrievalResult(
                            original.chunkId(),
                            original.content(),
                            rrfScore.get(entry.getKey()),
                            original.metadata()));
                });
        return fused;
    }
}
