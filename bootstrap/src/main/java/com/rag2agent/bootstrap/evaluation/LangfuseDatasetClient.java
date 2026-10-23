package com.rag2agent.bootstrap.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.LangfuseProperties;
import com.rag2agent.bootstrap.dto.EvaluationDtos.CaseInput;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 从 Langfuse 版本化 dataset 拉取评测用例，项目数据库仍保存运行事实。 */
@Service
public class LangfuseDatasetClient {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_CASES = 500;

    private final LangfuseProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    @Autowired
    public LangfuseDatasetClient(LangfuseProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build());
    }

    LangfuseDatasetClient(
            LangfuseProperties properties,
            ObjectMapper objectMapper,
            HttpClient http) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http = http;
    }

    public List<CaseInput> fetchCases(String requestedName) {
        requireConfigured();
        String datasetName = requestedName == null || requestedName.isBlank()
                ? properties.getDatasetName() : requestedName.trim();
        if (datasetName.isBlank()) {
            throw new IllegalArgumentException("Langfuse datasetName 不能为空");
        }
        List<CaseInput> cases = new ArrayList<>();
        for (int page = 1; cases.size() < MAX_CASES; page++) {
            JsonNode response = getPage(datasetName, page);
            JsonNode items = response.path("data");
            if (!items.isArray()) {
                throw new IllegalStateException("Langfuse dataset 响应缺少 data 数组");
            }
            for (JsonNode item : items) {
                cases.add(toCase(item));
                if (cases.size() >= MAX_CASES) {
                    break;
                }
            }
            int totalPages = response.path("meta").path("totalPages").asInt(page);
            if (items.isEmpty() || page >= totalPages) {
                break;
            }
        }
        return List.copyOf(cases);
    }

    CaseInput toCase(JsonNode item) {
        JsonNode input = item.path("input");
        String question = input.isTextual()
                ? input.asText("").trim()
                : input.path("question").asText("").trim();
        if (question.isBlank()) {
            throw new IllegalArgumentException(
                    "Langfuse dataset item 缺少 input.question: " + item.path("id").asText(""));
        }
        String expectedAnswer = expectedAnswer(item.path("expectedOutput"));
        JsonNode metadata = item.path("metadata");
        List<Long> goldenDocumentIds = longList(
                metadata.has("goldenDocumentIds")
                        ? metadata.path("goldenDocumentIds")
                        : metadata.path("golden_document_ids"));
        if (goldenDocumentIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Langfuse dataset item 缺少 metadata.goldenDocumentIds: "
                            + item.path("id").asText(""));
        }
        String caseType = metadata.path("caseType").asText(
                metadata.path("case_type").asText("RAG"));
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = metadata.path("evidence").isObject()
                ? objectMapper.convertValue(metadata.path("evidence"), Map.class)
                : Map.of();
        return new CaseInput(
                question, expectedAnswer, goldenDocumentIds, caseType, evidence);
    }

    private JsonNode getPage(String datasetName, int page) {
        StringBuilder url = new StringBuilder(trimSlash(properties.getBaseUrl()))
                .append("/api/public/dataset-items?datasetName=")
                .append(URLEncoder.encode(datasetName, StandardCharsets.UTF_8))
                .append("&page=").append(page)
                .append("&limit=").append(PAGE_SIZE);
        if (!properties.getDatasetVersion().isBlank()) {
            url.append("&version=")
                    .append(URLEncoder.encode(
                            properties.getDatasetVersion(), StandardCharsets.UTF_8));
        }
        String authorization = Base64.getEncoder().encodeToString(
                (properties.getPublicKey() + ":" + properties.getSecretKey())
                        .getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + authorization)
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(
                        "Langfuse dataset 请求失败: HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Langfuse dataset 请求被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Langfuse dataset 请求失败", exception);
        }
    }

    private String expectedAnswer(JsonNode expectedOutput) {
        if (expectedOutput == null || expectedOutput.isMissingNode() || expectedOutput.isNull()) {
            return null;
        }
        if (expectedOutput.isTextual()) {
            return expectedOutput.asText();
        }
        String answer = expectedOutput.path("answer").asText("").trim();
        return answer.isBlank() ? expectedOutput.toString() : answer;
    }

    private List<Long> longList(JsonNode values) {
        if (!values.isArray()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode value : values) {
            if (value.canConvertToLong()) {
                ids.add(value.longValue());
            }
        }
        return List.copyOf(ids);
    }

    private void requireConfigured() {
        if (!properties.isEnabled()
                || properties.getPublicKey().isBlank()
                || properties.getSecretKey().isBlank()) {
            throw new IllegalStateException("Langfuse 未启用或缺少 API 密钥");
        }
    }

    private String trimSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
