package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.tenancy.TenantContext;
import com.rag2agent.infra.ai.client.EmbeddingClient;
import com.rag2agent.infra.ai.model.EmbeddingRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentMemoryService {

    private final JdbcTemplate jdbc;
    private final EmbeddingClient embeddingClient;
    private TokenCostService tokenCostService;

    public AgentMemoryService(JdbcTemplate jdbc, EmbeddingClient embeddingClient) {
        this.jdbc = jdbc;
        this.embeddingClient = embeddingClient;
    }

    @Autowired(required = false)
    void setTokenCostService(TokenCostService tokenCostService) {
        this.tokenCostService = tokenCostService;
    }

    @Transactional
    public long remember(
            Long userId,
            Long kbId,
            String scope,
            String memoryType,
            String content,
            String source,
            double confidence,
            Duration ttl) {
        return remember(
                userId, kbId, scope, memoryType, content, source, confidence, ttl,
                entityKey(memoryType, content), memoryType, null, null);
    }

    @Transactional
    public long remember(
            Long userId,
            Long kbId,
            String scope,
            String memoryType,
            String content,
            String source,
            double confidence,
            Duration ttl,
            String entityKey,
            String predicate,
            String valueJson,
            Long sourceRunId) {
        if (userId == null || content == null || content.isBlank() || content.length() > 4000) {
            throw new IllegalArgumentException("记忆内容无效");
        }
        long tenantId = TenantContext.requireTenantId();
        String hash = sha256(content.trim());
        List<Long> duplicates = jdbc.query("""
                SELECT id FROM agent_memory
                WHERE user_id = ? AND tenant_id = ? AND COALESCE(kb_id, 0) = ?
                  AND content_hash = ? AND status = 'ACTIVE'
                LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1),
                userId, tenantId, kbId == null ? 0 : kbId, hash);
        if (!duplicates.isEmpty()) {
            long id = duplicates.getFirst();
            jdbc.update("""
                    UPDATE agent_memory
                    SET confidence = GREATEST(confidence, ?), last_accessed_at = now(),
                        updated_at = now()
                    WHERE id = ?
                    """, Math.clamp(confidence, 0, 1), id);
            event(id, userId, tenantId, "MERGE", "重复事实", hash);
            return id;
        }

        String normalizedEntity = entityKey == null || entityKey.isBlank()
                ? entityKey(memoryType, content) : entityKey.trim();
        String normalizedPredicate = predicate == null || predicate.isBlank()
                ? memoryType : predicate.trim();
        List<Long> conflicts = jdbc.query("""
                SELECT id FROM agent_memory
                WHERE user_id = ? AND tenant_id = ? AND COALESCE(kb_id, 0) = ?
                  AND memory_type = ? AND entity_key = ? AND predicate = ?
                  AND status = 'ACTIVE'
                ORDER BY updated_at DESC
                LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1),
                userId, tenantId, kbId == null ? 0 : kbId,
                memoryType, normalizedEntity, normalizedPredicate);

        List<Float> vector = embed(userId, content, "memory.write");
        Instant now = Instant.now();
        Instant expiresAt = ttl == null ? null : now.plus(ttl);
        Long id = jdbc.queryForObject("""
                INSERT INTO agent_memory
                    (user_id, kb_id, tenant_id, scope, memory_type, entity_key, predicate,
                     content, value_json, source, source_type, source_run_id, source_hash,
                     content_hash, confidence, status, event_at, recorded_at, valid_from,
                     expires_at, embedding)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, 'USER', ?, ?, ?,
                        ?, 'ACTIVE', ?, ?, ?, ?, CAST(? AS vector))
                RETURNING id
                """, Long.class,
                userId, kbId, tenantId, scope, memoryType, normalizedEntity,
                normalizedPredicate, content, valueJson, source, sourceRunId, hash, hash,
                Math.clamp(confidence, 0, 1), java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
                expiresAt == null ? null : java.sql.Timestamp.from(expiresAt),
                vector(vector));
        if (id == null) {
            throw new IllegalStateException("记忆写入失败");
        }
        if (conflicts.isEmpty()) {
            event(id, userId, tenantId, "ADD", null, hash);
        } else {
            long previous = conflicts.getFirst();
            jdbc.update("""
                    UPDATE agent_memory
                    SET status = 'SUPERSEDED', valid_to = now(), superseded_by = ?,
                        updated_at = now()
                    WHERE id = ? AND status = 'ACTIVE'
                    """, id, previous);
            jdbc.update("UPDATE agent_memory SET supersedes_id = ? WHERE id = ?", previous, id);
            event(id, userId, tenantId, "SUPERSEDE", "predicate=" + normalizedPredicate, hash);
        }
        return id;
    }

    public List<MemoryRecord> search(Long userId, Long kbId, String query, int topK) {
        long tenantId = TenantContext.requireTenantId();
        List<Float> vector = embed(userId, query, "memory.search");
        int boundedTopK = Math.min(Math.max(topK, 1), 20);
        List<MemoryRecord> records = jdbc.query("""
                SELECT id, kb_id, scope, memory_type, entity_key, predicate, content,
                       source, confidence, status, updated_at,
                       (1 - (embedding <=> CAST(? AS vector))) * 0.70
                         + similarity(content, ?) * 0.15
                         + confidence * 0.10
                         + 0.05 * exp(-extract(epoch FROM (
                               now() - COALESCE(last_accessed_at, updated_at)
                           )) / 2592000.0) AS score
                FROM agent_memory
                WHERE user_id = ? AND tenant_id = ?
                  AND (CAST(? AS BIGINT) IS NULL OR kb_id = ? OR kb_id IS NULL)
                  AND status = 'ACTIVE'
                  AND (expires_at IS NULL OR expires_at > now())
                  AND (1 - (embedding <=> CAST(? AS vector)) >= 0.10
                       OR similarity(content, ?) >= 0.10)
                ORDER BY score DESC
                LIMIT ?
                """, (rs, rowNum) -> record(rs),
                vector(vector), query, userId, tenantId, kbId, kbId,
                vector(vector), query, boundedTopK);
        if (!records.isEmpty()) {
            String ids = records.stream()
                    .map(record -> String.valueOf(record.id()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
            jdbc.update("""
                    UPDATE agent_memory
                    SET last_accessed_at = now()
                    WHERE user_id = ? AND tenant_id = ? AND id = ANY(CAST(? AS bigint[]))
                    """, userId, tenantId, ids);
        }
        return records;
    }

    public int forget(Long userId, Long memoryId) {
        long tenantId = TenantContext.requireTenantId();
        int deleted = jdbc.update(
                "DELETE FROM agent_memory WHERE id = ? AND user_id = ? AND tenant_id = ?",
                memoryId, userId, tenantId);
        if (deleted > 0) {
            event(memoryId, userId, tenantId, "DELETE", "user requested", null);
        }
        return deleted;
    }

    public List<MemoryRecord> list(Long userId, Long kbId, int limit) {
        long tenantId = TenantContext.requireTenantId();
        int boundedLimit = Math.min(Math.max(limit, 1), 100);
        return jdbc.query("""
                SELECT id, kb_id, scope, memory_type, entity_key, predicate, content,
                       source, confidence, status, updated_at, 0.0 AS score
                FROM agent_memory
                WHERE user_id = ? AND tenant_id = ?
                  AND (CAST(? AS BIGINT) IS NULL OR kb_id = ? OR kb_id IS NULL)
                  AND status = 'ACTIVE'
                  AND (expires_at IS NULL OR expires_at > now())
                ORDER BY updated_at DESC
                LIMIT ?
                """, (rs, rowNum) -> record(rs),
                userId, tenantId, kbId, kbId, boundedLimit);
    }

    public List<MemoryRecord> listForReview(Long userId, int limit) {
        long tenantId = TenantContext.requireTenantId();
        return jdbc.query("""
                SELECT id, kb_id, scope, memory_type, entity_key, predicate, content,
                       source, confidence, status, updated_at, 0.0 AS score
                FROM agent_memory
                WHERE user_id = ? AND tenant_id = ?
                  AND status IN ('CONFLICT', 'PENDING_REVIEW')
                ORDER BY updated_at DESC
                LIMIT ?
                """, (rs, rowNum) -> record(rs),
                userId, tenantId, Math.min(Math.max(limit, 1), 100));
    }

    @Transactional
    public boolean review(Long userId, Long memoryId, boolean approve) {
        long tenantId = TenantContext.requireTenantId();
        String status = approve ? "ACTIVE" : "REJECTED";
        int updated;
        try {
            updated = jdbc.update("""
                    UPDATE agent_memory
                    SET status = ?, valid_to = CASE WHEN ? THEN NULL ELSE now() END,
                        updated_at = now()
                    WHERE id = ? AND user_id = ? AND tenant_id = ?
                      AND status IN ('CONFLICT', 'PENDING_REVIEW')
                    """, status, approve, memoryId, userId, tenantId);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("同一内容已有有效记忆，不能重复审核通过");
        }
        if (updated == 1) {
            event(memoryId, userId, tenantId,
                    approve ? "REVIEW_APPROVE" : "REVIEW_REJECT",
                    "manual review", null);
        }
        return updated == 1;
    }

    private MemoryRecord record(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MemoryRecord(
                rs.getLong("id"),
                rs.getObject("kb_id", Long.class),
                rs.getString("scope"),
                rs.getString("memory_type"),
                rs.getString("entity_key"),
                rs.getString("predicate"),
                rs.getString("content"),
                rs.getString("source"),
                rs.getDouble("confidence"),
                rs.getString("status"),
                rs.getTimestamp("updated_at").toInstant().toString(),
                rs.getDouble("score"));
    }

    private void event(
            long memoryId,
            long userId,
            long tenantId,
            String action,
            String reason,
            String sourceHash) {
        jdbc.update("""
                INSERT INTO agent_memory_event
                    (memory_id, user_id, tenant_id, action, reason, source_hash)
                VALUES (?, ?, ?, ?, ?, ?)
                """, memoryId, userId, tenantId, action, reason, sourceHash);
    }

    private String entityKey(String memoryType, String content) {
        String normalized = content.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        String prefix = memoryType == null ? "memory" : memoryType.toLowerCase(Locale.ROOT);
        return (prefix + ":" + normalized.substring(0, Math.min(normalized.length(), 180)));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("记忆 hash 计算失败", exception);
        }
    }

    private List<Float> embed(Long userId, String text, String function) {
        Instant startedAt = Instant.now();
        TokenCostService.CostContext context = new TokenCostService.CostContext(
                java.util.UUID.randomUUID().toString(), null, null, userId,
                TenantContext.getTenantId(), function, startedAt);
        try {
            List<Float> vector = embeddingClient.embed(new EmbeddingRequest(
                    embeddingClient.providerName(), null, List.of(text))).vectors().getFirst();
            if (tokenCostService != null) {
                tokenCostService.recordExternal(
                        context,
                        embeddingClient.providerName(), embeddingClient.modelName(),
                        function, Math.max(1, text.length() / 2), 0);
            }
            return vector;
        } catch (RuntimeException exception) {
            if (tokenCostService != null) {
                tokenCostService.recordFailure(
                        context,
                        embeddingClient.providerName(), embeddingClient.modelName(), "FAILED");
            }
            throw exception;
        }
    }

    private String vector(List<Float> vector) {
        StringBuilder value = new StringBuilder("[");
        for (int index = 0; index < vector.size(); index++) {
            if (index > 0) {
                value.append(',');
            }
            value.append(vector.get(index));
        }
        return value.append(']').toString();
    }

    public record MemoryRecord(
            long id,
            Long kbId,
            String scope,
            String memoryType,
            String entityKey,
            String predicate,
            String content,
            String source,
            double confidence,
            String status,
            String updatedAt,
            double score) {}
}
