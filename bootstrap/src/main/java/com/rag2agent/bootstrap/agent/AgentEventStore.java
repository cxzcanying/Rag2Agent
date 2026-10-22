package com.rag2agent.bootstrap.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Agent 事件序列化失败", exception);
        }
    }
}
