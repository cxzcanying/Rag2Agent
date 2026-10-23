package com.rag2agent.bootstrap.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.LangfuseProperties;
import com.rag2agent.bootstrap.dto.EvaluationDtos.CaseResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Langfuse 是可选控制面；评测数据库仍是权威事实源。 */
@Service
public class LangfuseEvaluationPublisher {

    private static final Logger log = LoggerFactory.getLogger(LangfuseEvaluationPublisher.class);

    private final LangfuseProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public LangfuseEvaluationPublisher(
            LangfuseProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void publish(
            EvaluationRepository.EvalRun run,
            List<CaseResult> results,
            Double hitAtK,
            Double mrr) {
        if (!properties.isEnabled() || properties.getPublicKey().isBlank()
                || properties.getSecretKey().isBlank()) {
            return;
        }
        try {
            String traceId = "eval-" + run.id();
            List<Map<String, Object>> batch = new java.util.ArrayList<>();
            batch.add(Map.of(
                    "type", "trace-create",
                    "id", java.util.UUID.randomUUID().toString(),
                    "timestamp", Instant.now().toString(),
                    "body", Map.of(
                            "id", traceId,
                            "name", run.name(),
                            "input", Map.of("kbId", run.kbId(), "runId", run.id()),
                            "output", Map.of("hitAtK", hitAtK, "mrr", mrr),
                            "metadata", Map.of("source", "rag2agent", "config", run.configJson()))));
            for (CaseResult result : results) {
                batch.add(Map.of(
                        "type", "score-create",
                        "id", java.util.UUID.randomUUID().toString(),
                        "timestamp", Instant.now().toString(),
                        "body", Map.of(
                                "traceId", traceId,
                                "name", "retrieval_rank",
                                "value", result.firstRelevantRank(),
                                "comment", result.question())));
            }
            String body = objectMapper.writeValueAsString(Map.of("batch", batch));
            String auth = Base64.getEncoder().encodeToString(
                    (properties.getPublicKey() + ":" + properties.getSecretKey())
                            .getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(trimSlash(properties.getBaseUrl()) + "/api/public/ingestion"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 400) {
                            log.warn("Langfuse 评测上报失败: status={}", response.statusCode());
                        }
                    });
        } catch (Exception exception) {
            log.warn("Langfuse 评测上报准备失败", exception);
        }
    }

    private String trimSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
