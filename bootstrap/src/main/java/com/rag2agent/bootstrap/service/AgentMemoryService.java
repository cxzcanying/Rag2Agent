package com.rag2agent.bootstrap.service;

import com.rag2agent.infra.ai.client.EmbeddingClient;
import com.rag2agent.infra.ai.model.EmbeddingRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** PostgreSQL + pgvector 长期记忆，所有查询强制用户作用域。 */
@Service
public class AgentMemoryService {

    private final JdbcTemplate jdbc;
    private final EmbeddingClient embeddingClient;

    public AgentMemoryService(JdbcTemplate jdbc, EmbeddingClient embeddingClient) {
        this.jdbc = jdbc;
        this.embeddingClient = embeddingClient;
    }

    public long remember(
            Long userId,
            Long kbId,
            String scope,
            String memoryType,
            String content,
            String source,
            double confidence,
            Duration ttl) {
        if (userId == null || content == null || content.isBlank() || content.length() > 4000) {
            throw new IllegalArgumentException("记忆内容无效");
        }
        List<Float> vector = embeddingClient.embed(new EmbeddingRequest(
                embeddingClient.providerName(), null, List.of(content))).vectors().getFirst();
        Instant expiresAt = ttl == null ? null : Instant.now().plus(ttl);
        Long id = jdbc.queryForObject("""
                INSERT INTO agent_memory
                    (user_id, kb_id, scope, memory_type, content, source,
                     confidence, embedding, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?)
                RETURNING id
                """, Long.class,
                userId, kbId, scope, memoryType, content, source, confidence,
                vector(vector), expiresAt == null ? null : java.sql.Timestamp.from(expiresAt));
        if (id == null) {
            throw new IllegalStateException("记忆写入失败");
        }
        return id;
    }

    public List<MemoryRecord> search(
            Long userId, Long kbId, String query, int topK) {
        List<Float> vector = embeddingClient.embed(new EmbeddingRequest(
                embeddingClient.providerName(), null, List.of(query))).vectors().getFirst();
        return jdbc.query("""
                SELECT id, kb_id, scope, memory_type, content, source, confidence,
                       1 - (embedding <=> CAST(? AS vector)) AS score
                FROM agent_memory
                WHERE user_id = ?
                  AND (kb_id = ? OR kb_id IS NULL)
                  AND (expires_at IS NULL OR expires_at > now())
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT ?
                """, (rs, rowNum) -> new MemoryRecord(
                        rs.getLong("id"),
                        rs.getObject("kb_id", Long.class),
                        rs.getString("scope"),
                        rs.getString("memory_type"),
                        rs.getString("content"),
                        rs.getString("source"),
                        rs.getDouble("confidence"),
                        rs.getDouble("score")),
                vector(vector), userId, kbId, vector(vector), topK);
    }

    public int forget(Long userId, Long memoryId) {
        return jdbc.update(
                "DELETE FROM agent_memory WHERE id = ? AND user_id = ?", memoryId, userId);
    }

    public List<MemoryRecord> list(Long userId, Long kbId, int limit) {
        int boundedLimit = Math.min(Math.max(limit, 1), 100);
        return jdbc.query("""
                SELECT id, kb_id, scope, memory_type, content, source, confidence,
                       0.0 AS score
                FROM agent_memory
                WHERE user_id = ?
                  AND (kb_id = ? OR kb_id IS NULL)
                  AND (expires_at IS NULL OR expires_at > now())
                ORDER BY updated_at DESC
                LIMIT ?
                """, (rs, rowNum) -> new MemoryRecord(
                        rs.getLong("id"),
                        rs.getObject("kb_id", Long.class),
                        rs.getString("scope"),
                        rs.getString("memory_type"),
                        rs.getString("content"),
                        rs.getString("source"),
                        rs.getDouble("confidence"),
                        rs.getDouble("score")),
                userId, kbId, boundedLimit);
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
            String content,
            String source,
            double confidence,
            double score) {}
}
