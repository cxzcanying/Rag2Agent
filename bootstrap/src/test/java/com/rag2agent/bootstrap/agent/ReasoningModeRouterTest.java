package com.rag2agent.bootstrap.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ReasoningModeRouterTest {

    @Test
    void autoUsesHigherEffortForComplexCrossDocumentQuestions() {
        assertEquals("low", ReasoningModeRouter.resolve("auto", "公司地址是什么？"));
        assertEquals("high", ReasoningModeRouter.resolve(
                "auto", "请比较这些跨文档报告的营收变化并分析原因，说明推理依据。"));
    }

    @Test
    void rejectsUnknownReasoningMode() {
        assertThrows(IllegalArgumentException.class,
                () -> ReasoningModeRouter.resolve("turbo", "问题"));
    }
}
