package com.rag2agent.bootstrap.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentTaskService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AgentTaskService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long create(
            Long runId,
            Long parentTaskId,
            String assignee,
            String taskType,
            String title,
            String instruction,
            List<Long> dependsOn) {
        Long id = jdbc.queryForObject("""
                INSERT INTO agent_task
                    (run_id, parent_task_id, assignee, task_type, title, instruction, depends_on)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS bigint[]))
                RETURNING id
                """, Long.class,
                runId, parentTaskId, assignee, taskType, title, instruction,
                dependsOn.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}")));
        if (id == null) {
            throw new IllegalStateException("Agent 任务创建失败");
        }
        return id;
    }

    @Transactional
    public List<AgentTask> claimReady(Long runId, String assignee, int limit) {
        return jdbc.query("""
                WITH ready AS (
                    SELECT task.id
                    FROM agent_task task
                    WHERE task.run_id = ?
                      AND task.status = 'PENDING'
                      AND NOT EXISTS (
                          SELECT 1
                          FROM unnest(task.depends_on) dependency_id
                          JOIN agent_task dependency ON dependency.id = dependency_id
                          WHERE dependency.status <> 'COMPLETED'
                      )
                    ORDER BY task.id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE agent_task task
                SET status = 'RUNNING',
                    assignee = ?,
                    started_at = now(),
                    updated_at = now()
                FROM ready
                WHERE task.id = ready.id
                RETURNING task.*
                """, (rs, rowNum) -> map(rs), runId, limit, assignee);
    }

    public int start(Long taskId, String assignee) {
        return jdbc.update("""
                UPDATE agent_task
                SET status = 'RUNNING', assignee = ?, started_at = now(), updated_at = now()
                WHERE id = ? AND status = 'PENDING'
                """, assignee, taskId);
    }

    public List<AgentTask> list(Long runId) {
        return jdbc.query(
                "SELECT * FROM agent_task WHERE run_id = ? ORDER BY id",
                (rs, rowNum) -> map(rs), runId);
    }

    public int complete(Long taskId, Object output) {
        return jdbc.update("""
                UPDATE agent_task
                SET status = 'COMPLETED', output = CAST(? AS jsonb),
                    error_message = NULL, completed_at = now(), updated_at = now()
                WHERE id = ? AND status = 'RUNNING'
                """, json(output), taskId);
    }

    @Transactional
    public int fail(Long taskId, String error) {
        int failed = jdbc.update("""
                UPDATE agent_task
                SET status = 'FAILED', error_message = ?,
                    completed_at = now(), updated_at = now()
                WHERE id = ? AND status IN ('PENDING', 'RUNNING')
                """, error, taskId);
        if (failed == 1) {
            blockDependents(taskId, "上游任务失败: " + error);
        }
        return failed;
    }

    @Transactional
    public int cancelRun(Long runId, String reason) {
        int cancelled = jdbc.update("""
                UPDATE agent_task
                SET status = 'CANCELLED', error_message = ?,
                    completed_at = now(), updated_at = now()
                WHERE run_id = ? AND status IN ('PENDING', 'RUNNING')
                """, reason, runId);
        return cancelled;
    }

    private AgentTask map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AgentTask(
                rs.getLong("id"),
                rs.getLong("run_id"),
                rs.getObject("parent_task_id", Long.class),
                rs.getString("assignee"),
                rs.getString("task_type"),
                rs.getString("title"),
                rs.getString("instruction"),
                rs.getString("status"),
                longs(rs.getArray("depends_on")),
                rs.getString("output"),
                rs.getString("error_message"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("completed_at")),
                instant(rs.getTimestamp("created_at")));
    }

    private void blockDependents(long failedTaskId, String reason) {
        jdbc.update("""
                WITH RECURSIVE affected AS (
                    SELECT id
                    FROM agent_task
                    WHERE ?::bigint = ANY(depends_on)
                    UNION
                    SELECT task.id
                    FROM agent_task task
                    JOIN affected ON affected.id = ANY(task.depends_on)
                )
                UPDATE agent_task
                SET status = 'BLOCKED', error_message = ?,
                    completed_at = now(), updated_at = now()
                WHERE id IN (SELECT id FROM affected)
                  AND status = 'PENDING'
                """, failedTaskId, reason);
    }

    private List<Long> longs(Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        Object value = array.getArray();
        if (value instanceof Long[] longs) {
            return List.of(longs);
        }
        if (value instanceof Number[] numbers) {
            return Arrays.stream(numbers).map(Number::longValue).toList();
        }
        return List.of();
    }

    private Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Agent 任务结果序列化失败", exception);
        }
    }

    public record AgentTask(
            long id,
            long runId,
            Long parentTaskId,
            String assignee,
            String taskType,
            String title,
            String instruction,
            String status,
            List<Long> dependsOn,
            String output,
            String errorMessage,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt) {}
}
