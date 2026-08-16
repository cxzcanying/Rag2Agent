package com.rag2agent.rag.core.retrieval.impl;

import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）多路召回融合。
 * 对每一路排序结果，排名第 rank 的文档贡献 1/(k + rank + 1)，k 取 60（决定"排名差距"被放大多少）。
 * 同一 chunk 出现在多路时分数累加，实现去重 + 综合排序。
 * @author 21311
 */
public final class RrfFusion {

    private static final double K = 60.0;

    private RrfFusion() {}

    /**
     * 融合多路召回结果，返回按 RRF 分数降序、去重后的 topK 条。
     * 两阶段：
     * 1) 遍历各路，累加每个 chunk 的 1/(k+rank+1) 贡献（同名跨路累加）；
     * 2) 按融合分降序排序，截取 topK，用融合分替换原始分数输出。
     */
    public static List<RetrievalResult> fuse(List<List<RetrievalResult>> rankedLists, int topK) {
        // resultById：chunkId -> 首次遇到的原始结果（含引用溯源 metadata）
        // rrfScore：chunkId -> 跨路累加的 RRF 分数
        Map<String, RetrievalResult> resultById = new LinkedHashMap<>();
        Map<String, Double> rrfScore = new LinkedHashMap<>();
        // 第一阶段：遍历每一路、每一个排名，累加贡献。
        // 只看排名不看原始分数，因此多路分数域不同也能直接融合
        for (List<RetrievalResult> rankedList : rankedLists) {
            for (int rank = 0; rank < rankedList.size(); rank++) {
                RetrievalResult result = rankedList.get(rank);
                // 排名第 rank 的贡献：k=60 时第一名 1/61，第二名 1/62，差距被压平，
                // 效果是"多路都出现"比"单路排第一"更重要
                double contribution = 1.0 / (K + rank + 1);
                // 同一 chunk 在多路出现时分数累加
                rrfScore.merge(result.chunkId(), contribution, Double::sum);
                // putIfAbsent：只保留第一次遇到的结果，避免重复覆盖（引用溯源信息不丢）
                resultById.putIfAbsent(result.chunkId(), result);
            }
        }
        List<RetrievalResult> fused = new ArrayList<>();
        // 第二阶段：按 RRF 分数降序排序（b 减 a 得到降序；Double.compare 避免精度/NaN 问题）
        resultById.entrySet().stream()
                .sorted((a, b) -> Double.compare(rrfScore.get(b.getKey()), rrfScore.get(a.getKey())))
                // 只保留前 topK 条，后续交给 rerank 精排
                .limit(topK)
                .forEach(entry -> {
                    RetrievalResult original = entry.getValue();
                    // record 不可变，必须 new 一个新对象：
                    // chunkId/content/metadata 原样保留，score 替换为融合后的 RRF 分数
                    fused.add(new RetrievalResult(
                            original.chunkId(),
                            original.content(),
                            rrfScore.get(entry.getKey()),
                            original.metadata()));
                });
        return fused;
    }
}
