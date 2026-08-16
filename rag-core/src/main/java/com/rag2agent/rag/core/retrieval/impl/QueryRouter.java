package com.rag2agent.rag.core.retrieval.impl;

/**
 * Query Routing 规则版：按查询特征决定检索路由。
 * <ul>
 *   <li>SEMANTIC：含疑问词（什么/如何/为什么等），语义型问题 → 向量检索为主；</li>
 *   <li>KEYWORD：短词/编号/专有名词（≤12 字符且非问句）→ 关键词检索为主；</li>
 *   <li>HYBRID：其余情况 → 混合检索。</li>
 * </ul>
 * 后续可由小模型分类器替换，当前规则版足够覆盖常见场景并省一次 embedding 调用。
 */
public final class QueryRouter {

    private static final int KEYWORD_MAX_LENGTH = 12;

    public enum Route {
        KEYWORD,
        SEMANTIC,
        HYBRID
    }

    private QueryRouter() {}

    public static Route route(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return Route.HYBRID;
        }
        boolean questionLike = q.matches(".*(什么|如何|怎么|为什么|哪些|是什么|怎么样).*");
        if (questionLike) {
            return Route.SEMANTIC;
        }
        if (q.length() <= KEYWORD_MAX_LENGTH) {
            return Route.KEYWORD;
        }
        return Route.HYBRID;
    }
}
