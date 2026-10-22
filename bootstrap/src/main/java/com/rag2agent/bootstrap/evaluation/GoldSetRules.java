package com.rag2agent.bootstrap.evaluation;

import com.rag2agent.bootstrap.dto.EvaluationDtos.CaseInput;
import com.rag2agent.bootstrap.dto.EvaluationDtos.GoldSetCoverage;
import com.rag2agent.bootstrap.dto.EvaluationDtos.GoldSetViolation;
import com.rag2agent.bootstrap.evaluation.EvaluationRepository.EvalCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 五类 Gold Set 的最小证据契约；没有可定位证据的用例不能进入发布门禁。 */
public final class GoldSetRules {

    private static final List<String> CATEGORIES =
            List.of("RAG", "TABLE", "MULTIMODAL", "GRAPH", "AGENT");

    private GoldSetRules() {}

    static List<GoldSetViolation> validateInputs(List<CaseInput> cases) {
        List<GoldSetViolation> violations = new ArrayList<>();
        for (int index = 0; index < cases.size(); index++) {
            CaseInput item = cases.get(index);
            String type = normalizeType(item.caseType());
            String error = evidenceError(type, item.evidence());
            if (error != null) {
                violations.add(new GoldSetViolation(
                        (long) index + 1, type, "第 " + (index + 1) + " 条用例：" + error));
            }
        }
        return List.copyOf(violations);
    }

    static GoldSetCoverage coverage(List<EvalCase> cases) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        CATEGORIES.forEach(type -> counts.put(type, 0));
        List<GoldSetViolation> violations = new ArrayList<>();
        for (EvalCase item : cases) {
            String type = normalizeType(item.caseType());
            if (!counts.containsKey(type)) {
                violations.add(new GoldSetViolation(
                        item.id(), type, "未知 Gold Set 类型: " + type));
                continue;
            }
            counts.merge(type, 1, Integer::sum);
            String error = evidenceError(type, item.evidence());
            if (error != null) {
                violations.add(new GoldSetViolation(item.id(), type, error));
            }
        }
        List<String> missing = counts.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .toList();
        return new GoldSetCoverage(cases.size(), Map.copyOf(counts), missing, List.copyOf(violations));
    }

    private static String evidenceError(String type, Map<String, Object> evidence) {
        Map<String, Object> values = evidence == null ? Map.of() : evidence;
        return switch (type) {
            case "RAG" -> null;
            case "TABLE" -> requireTable(values);
            case "MULTIMODAL" -> requireList(values, "assetIds", "assetIds 不能为空");
            case "GRAPH" -> requireGraph(values);
            case "AGENT" -> requireAgent(values);
            default -> "caseType 必须为 " + String.join("/", CATEGORIES);
        };
    }

    private static String requireTable(Map<String, Object> evidence) {
        if (!(evidence.get("tableId") instanceof String tableId) || tableId.isBlank()) {
            return "TABLE 用例必须提供 evidence.tableId";
        }
        String headers = requireList(evidence, "headers", "TABLE 用例必须提供 evidence.headers");
        if (headers != null) {
            return headers;
        }
        return requireList(evidence, "rowIds", "TABLE 用例必须提供 evidence.rowIds");
    }

    private static String requireGraph(Map<String, Object> evidence) {
        String entities = requireList(evidence, "entities", "GRAPH 用例必须提供 evidence.entities");
        if (entities != null) {
            return entities;
        }
        if (!(evidence.get("maxHops") instanceof Number hops) || hops.intValue() < 1) {
            return "GRAPH 用例必须提供正数 evidence.maxHops";
        }
        return null;
    }

    private static String requireAgent(Map<String, Object> evidence) {
        String tools = requireList(
                evidence, "requiredTools", "AGENT 用例必须提供 evidence.requiredTools");
        if (tools != null) {
            return tools;
        }
        if (!(evidence.get("maxSteps") instanceof Number steps) || steps.intValue() < 1) {
            return "AGENT 用例必须提供正数 evidence.maxSteps";
        }
        return null;
    }

    private static String requireList(
            Map<String, Object> evidence, String key, String message) {
        return evidence.get(key) instanceof List<?> values && !values.isEmpty() ? null : message;
    }

    private static String normalizeType(String value) {
        return value == null || value.isBlank()
                ? "RAG"
                : value.trim().toUpperCase(Locale.ROOT);
    }
}
