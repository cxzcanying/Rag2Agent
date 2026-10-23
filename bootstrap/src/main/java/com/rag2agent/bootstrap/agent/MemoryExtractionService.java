package com.rag2agent.bootstrap.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.MemoryExtractionProperties;
import com.rag2agent.bootstrap.service.AgentMemoryService;
import com.rag2agent.bootstrap.service.TokenCostService;
import com.rag2agent.bootstrap.tenancy.TenantContext;
import com.rag2agent.infra.ai.client.ChatModelClient;
import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import com.rag2agent.infra.ai.model.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 从已完成的 Agent 运行中抽取少量明确事实，后台写入长期记忆。 */
@Service
public class MemoryExtractionService {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionService.class);
    private static final int MAX_SOURCE_CHARS = 12_000;

    private final JdbcTemplate jdbc;
    private final ChatModelClient chatClient;
    private final AgentMemoryService memoryService;
    private final TokenCostService tokenCostService;
    private final ObjectMapper objectMapper;
    private final MemoryExtractionProperties properties;
    private final Executor executor;

    public MemoryExtractionService(
            JdbcTemplate jdbc,
            ChatModelClient chatClient,
            AgentMemoryService memoryService,
            TokenCostService tokenCostService,
            ObjectMapper objectMapper,
            MemoryExtractionProperties properties,
            @Qualifier("memoryTaskExecutor") Executor executor) {
        this.jdbc = jdbc;
        this.chatClient = chatClient;
        this.memoryService = memoryService;
        this.tokenCostService = tokenCostService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.executor = executor;
    }

    public void submit(Long runId) {
        if (!properties.isEnabled() || runId == null) {
            return;
        }
        List<Long> ids = jdbc.query("""
                INSERT INTO agent_memory_job(run_id, user_id, tenant_id, kb_id)
                SELECT run.id, run.user_id, kb.tenant_id, run.kb_id
                FROM agent_run run
                JOIN knowledge_base kb ON kb.id = run.kb_id
                WHERE run.id = ?
                  AND run.answer IS NOT NULL
                  AND run.status IN ('COMPLETED', 'DEGRADED', 'MAX_STEPS_REACHED')
                ON CONFLICT (run_id) DO UPDATE
                SET status = 'PENDING', updated_at = now()
                WHERE agent_memory_job.status = 'FAILED'
                RETURNING id
                """, (rs, rowNum) -> rs.getLong(1), runId);
        if (!ids.isEmpty()) {
            schedule(ids.getFirst());
        }
    }

    @Scheduled(
            initialDelay = 15000,
            fixedDelayString = "${rag2agent.memory.extraction.scan-interval-ms:60000}")
    public void recoverPendingJobs() {
        if (!properties.isEnabled()) {
            return;
        }
        jdbc.update("""
                UPDATE agent_memory_job
                SET status = 'PENDING', updated_at = now()
                WHERE status = 'RUNNING' AND started_at < now() - interval '15 minutes'
                """);
        jdbc.query("""
                SELECT id FROM agent_memory_job
                WHERE status = 'PENDING'
                ORDER BY id
                LIMIT 20
                """, (rs, rowNum) -> rs.getLong(1))
                .forEach(this::schedule);
    }

    private void schedule(Long jobId) {
        try {
            executor.execute(() -> TenantContext.runAsSystem(() -> execute(jobId)));
        } catch (RuntimeException exception) {
            log.warn("记忆抽取任务入队失败: jobId={}", jobId);
        }
    }

    private void execute(Long jobId) {
        Job job = claim(jobId);
        if (job == null) {
            return;
        }
        try {
            TenantContext.runAs(job.tenantId(), () -> extract(job));
        } catch (RuntimeException exception) {
            jdbc.update("""
                    UPDATE agent_memory_job
                    SET status = 'FAILED', error_message = ?, completed_at = now(), updated_at = now()
                    WHERE id = ?
                    """, truncate(exception.getMessage()), jobId);
            log.warn("记忆抽取失败: jobId={}, runId={}", jobId, job.runId());
        }
    }

    private void extract(Job job) {
        AgentSource source = jdbc.queryForObject("""
                SELECT query, answer FROM agent_run WHERE id = ?
                """, (rs, rowNum) -> new AgentSource(
                rs.getString("query"), rs.getString("answer")), job.runId());
        if (source == null || source.answer() == null || source.answer().isBlank()) {
            throw new IllegalStateException("Agent 运行缺少可抽取内容");
        }
        String userContent = limit("用户问题：\n" + source.query()
                + "\n\n助手回答：\n" + source.answer());
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", """
                        从对话中抽取值得长期保存的原子事实。只记录用户明确表达的偏好、决策、稳定事实，
                        或助手基于已验证工具结果确认的事实。不要记录推测、寒暄、临时问题或系统提示。
                        最多输出 %d 条，confidence 为 0 到 1。
                        只输出 JSON：{"facts":[{"content":"事实","memory_type":"FACT|PREFERENCE|DECISION",
                        "entity_key":"实体键","predicate":"谓词","confidence":0.0}]}
                        """.formatted(properties.getMaxFacts())),
                new ChatMessage("user", userContent));
        java.time.Instant startedAt = java.time.Instant.now();
        var cost = new TokenCostService.CostContext(
                UUID.randomUUID().toString(), null, job.runId(), job.userId(),
                job.tenantId(), "memory.extraction", startedAt);
        ChatCompletionResponse response;
        try {
            response = chatClient.complete(new ChatCompletionRequest(
                    chatClient.providerName(), null, messages,
                    Map.of("temperature", 0.0, "max_tokens", 800)));
        } catch (RuntimeException exception) {
            tokenCostService.recordFailure(
                    cost, chatClient.providerName(), chatClient.modelName(), "FAILED");
            throw exception;
        }
        tokenCostService.record(
                cost, response.usage(), messages, List.of(),
                chatClient.providerName(), chatClient.modelName());
        List<Fact> facts = parseFacts(response.content());
        int saved = 0;
        for (Fact fact : facts.stream().limit(properties.getMaxFacts()).toList()) {
            if (fact.confidence() < properties.getMinConfidence()
                    || fact.content() == null || fact.content().isBlank()) {
                continue;
            }
            memoryService.remember(
                    job.userId(), job.kbId(), "KB", fact.memoryType(),
                    fact.content(), "agent:auto", fact.confidence(), null,
                    fact.entityKey(), fact.predicate(), null, job.runId());
            saved++;
        }
        jdbc.update("""
                UPDATE agent_memory_job
                SET status = 'COMPLETED', extracted_count = ?, completed_at = now(),
                    error_message = NULL, updated_at = now()
                WHERE id = ?
                """, saved, job.id());
    }

    List<Fact> parseFacts(String raw) {
        try {
            String json = stripFence(raw);
            JsonNode root = objectMapper.readTree(json);
            JsonNode array = root.isArray() ? root : root.path("facts");
            List<Fact> facts = new ArrayList<>();
            if (!array.isArray()) {
                return List.of();
            }
            for (JsonNode item : array) {
                String type = item.path("memory_type")
                        .asText("FACT").toUpperCase(java.util.Locale.ROOT);
                if (!List.of("FACT", "PREFERENCE", "DECISION").contains(type)) {
                    type = "FACT";
                }
                facts.add(new Fact(
                        item.path("content").asText("").trim(),
                        type,
                        item.path("entity_key").asText("").trim(),
                        item.path("predicate").asText("").trim(),
                        item.path("confidence").asDouble(0.0)));
            }
            return List.copyOf(facts);
        } catch (JsonProcessingException exception) {
            log.warn("记忆抽取返回的 JSON 无效", exception);
            return List.of();
        }
    }

    private Job claim(Long jobId) {
        List<Job> jobs = jdbc.query("""
                WITH claimed AS (
                    UPDATE agent_memory_job
                    SET status = 'RUNNING', started_at = now(), updated_at = now()
                    WHERE id = ? AND status = 'PENDING'
                    RETURNING *
                )
                SELECT * FROM claimed
                """, (rs, rowNum) -> new Job(
                rs.getLong("id"),
                rs.getLong("run_id"),
                rs.getLong("user_id"),
                rs.getLong("tenant_id"),
                rs.getLong("kb_id")), jobId);
        return jobs.isEmpty() ? null : jobs.getFirst();
    }

    private String stripFence(String value) {
        if (value == null) {
            return "{}";
        }
        String text = value.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private String limit(String value) {
        return value.length() <= MAX_SOURCE_CHARS
                ? value : value.substring(0, MAX_SOURCE_CHARS);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }

    record Fact(
            String content,
            String memoryType,
            String entityKey,
            String predicate,
            double confidence) {}

    private record Job(
            long id, long runId, long userId, long tenantId, long kbId) {}

    private record AgentSource(String query, String answer) {}
}
