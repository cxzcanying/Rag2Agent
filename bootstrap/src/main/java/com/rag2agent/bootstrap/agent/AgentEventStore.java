package com.rag2agent.bootstrap.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 持久 Agent 事件日志。事件是运行轨迹的权威来源，SSE 只是实时投影。 */
@Service
public class AgentEventStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AgentEventStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void ensureSession(Long userId, String sessionId) {
        jdbc.update("""
                INSERT INTO agent_session (user_id, session_id)
                VALUES (?, ?)
                ON CONFLICT (user_id, session_id)
                DO UPDATE SET last_active_at = now(), updated_at = now()
                """, userId, sessionId);
    }

    public long append(
            Long runId,
            Long userId,
            String sessionId,
            String eventType,
            Object payload) {
        Long seq = jdbc.queryForObject("""
                INSERT INTO agent_event
                    (run_id, user_id, session_id, seq, event_type, payload)
                SELECT ?, ?, ?, COALESCE(MAX(seq), 0) + 1, ?, CAST(? AS jsonb)
                FROM agent_event
                WHERE user_id = ? AND session_id = ?
                RETURNING seq
                """, Long.class,
                runId, userId, sessionId, eventType, json(payload), userId, sessionId);
        if (seq == null) {
            throw new IllegalStateException("Agent 事件写入失败");
        }
        return seq;
    }

    public void checkpoint(Long runId, String sessionId, long eventSeq, Map<String, Object> state) {
        jdbc.update("""
                INSERT INTO agent_checkpoint (run_id, session_id, event_seq, state)
                VALUES (?, ?, ?, CAST(? AS jsonb))
                """, runId, sessionId, eventSeq, json(state));
    }

    public void saveSummary(
            Long userId,
            String sessionId,
            long sourceFromSeq,
            long sourceToSeq,
            String version,
            String summary,
            String requestId) {
        jdbc.update("""
                INSERT INTO agent_session_summary
                    (user_id, tenant_id, session_id, source_from_seq, source_to_seq,
                     version, summary, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, userId,
                com.rag2agent.bootstrap.tenancy.TenantContext.requireTenantId(),
                sessionId, sourceFromSeq, sourceToSeq, version, summary, requestId);
    }

    public List<EventRecord> listEvents(
            Long userId, String sessionId, long afterSeq, int limit) {
        return jdbc.query("""
                SELECT id, run_id, seq, event_type, CAST(payload AS text) AS payload, created_at
                FROM agent_event
                WHERE user_id = ? AND session_id = ? AND seq > ?
                ORDER BY seq
                LIMIT ?
                """, (rs, rowNum) -> new EventRecord(
                        rs.getLong("id"),
                        rs.getLong("run_id"),
                        rs.getLong("seq"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant()),
                userId, sessionId, afterSeq, limit);
    }

    public List<SessionTurn> recentTurns(Long userId, String sessionId, int limit) {
        return jdbc.query("""
                SELECT query, answer
                FROM agent_run
                WHERE user_id = ? AND session_id = ?
                  AND answer IS NOT NULL
                  AND status IN ('COMPLETED', 'DEGRADED', 'MAX_STEPS_REACHED')
                ORDER BY created_at DESC
                LIMIT ?
                """, (rs, rowNum) -> new SessionTurn(rs.getString("query"), rs.getString("answer")),
                userId, sessionId, Math.min(Math.max(limit, 1), 12))
                .reversed();
    }

    public SessionSummary latestSummary(Long userId, String sessionId) {
        List<SessionSummary> summaries = jdbc.query("""
                SELECT source_from_seq, source_to_seq, version, summary, request_id
                FROM agent_session_summary
                WHERE user_id = ? AND tenant_id = ? AND session_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """, (rs, rowNum) -> new SessionSummary(
                        rs.getLong("source_from_seq"),
                        rs.getLong("source_to_seq"),
                        rs.getString("version"),
                        rs.getString("summary"),
                        rs.getString("request_id")),
                userId,
                com.rag2agent.bootstrap.tenancy.TenantContext.requireTenantId(),
                sessionId);
        return summaries.isEmpty() ? null : summaries.getFirst();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Agent 事件序列化失败", exception);
        }
    }

    public record EventRecord(
            long id,
            long runId,
            long seq,
            String type,
            String payload,
            java.time.Instant createdAt) {}

    public record SessionTurn(String query, String answer) {}

    public record SessionSummary(
            long sourceFromSeq,
            long sourceToSeq,
            String version,
            String summary,
            String requestId) {}
}
