package com.rag2agent.rag.core.retrieval.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rag2agent.rag.core.retrieval.impl.QueryRouter.Route;
import org.junit.jupiter.api.Test;

class QueryRouterTest {

    @Test
    void questionGoesSemantic() {
        assertEquals(Route.SEMANTIC, QueryRouter.route("Python 的数据类型有哪些"));
        assertEquals(Route.SEMANTIC, QueryRouter.route("什么是 RAG"));
    }

    @Test
    void shortTermGoesKeyword() {
        assertEquals(Route.KEYWORD, QueryRouter.route("HashMap"));
        assertEquals(Route.KEYWORD, QueryRouter.route("RAG"));
    }

    @Test
    void otherGoesHybrid() {
        assertEquals(Route.HYBRID, QueryRouter.route("介绍一下分布式系统的常见设计原则"));
    }
}
