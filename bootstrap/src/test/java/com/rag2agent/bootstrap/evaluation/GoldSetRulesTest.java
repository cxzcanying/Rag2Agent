package com.rag2agent.bootstrap.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.dto.EvaluationDtos.CaseInput;
import com.rag2agent.bootstrap.evaluation.EvaluationRepository.EvalCase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoldSetRulesTest {

    @Test
    void validatesAllFiveGoldSetCategories() {
        List<CaseInput> cases = List.of(
                input("RAG", Map.of()),
                input("TABLE", Map.of(
                        "tableId", "table-2",
                        "headers", List.of("指标", "2025", "2026"),
                        "rowIds", List.of("row-7"))),
                input("MULTIMODAL", Map.of("assetIds", List.of("image-3"))),
                input("GRAPH", Map.of("entities", List.of("RAG2Agent", "Neo4j"), "maxHops", 2)),
                input("AGENT", Map.of("requiredTools", List.of("search_knowledge_base"), "maxSteps", 4)));

        assertTrue(GoldSetRules.validateInputs(cases).isEmpty());
    }

    @Test
    void tableCaseWithoutHeadersOrRowsIsRejected() {
        var violations = GoldSetRules.validateInputs(List.of(
                input("TABLE", Map.of("tableId", "table-2", "headers", List.of()))));

        assertFalse(violations.isEmpty());
        assertTrue(violations.getFirst().message().contains("headers"));
    }

    @Test
    void coverageReportsMissingCategoriesAndInvalidEvidence() {
        List<EvalCase> cases = List.of(
                new EvalCase(1L, 2L, "普通问题", "答案", List.of(9L), "RAG", Map.of()),
                new EvalCase(2L, 2L, "表格问题", "答案", List.of(9L), "TABLE", Map.of("tableId", "t1")));

        var coverage = GoldSetRules.coverage(cases);

        assertEquals(2, coverage.totalCases());
        assertTrue(coverage.missingCategories().contains("GRAPH"));
        assertFalse(coverage.violations().isEmpty());
        assertFalse(coverage.valid());
    }

    @Test
    void validFlagIsSerializedForFrontendGate() throws Exception {
        var coverage = new com.rag2agent.bootstrap.dto.EvaluationDtos.GoldSetCoverage(
                1, Map.of("RAG", 1), List.of(), List.of(), true);

        String json = new ObjectMapper().writeValueAsString(coverage);

        assertTrue(json.contains("\"valid\":true"));
    }

    private static CaseInput input(String type, Map<String, Object> evidence) {
        return new CaseInput("问题-" + type, "答案", List.of(1L), type, evidence);
    }
}
