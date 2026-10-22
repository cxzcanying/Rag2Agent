package com.rag2agent.bootstrap.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AgentTaskServiceTest {

    @Test
    void createsTaskWithDependenciesAndCompletesWithJsonOutput() {
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public <T> T queryForObject(
                    String sql, Class<T> requiredType, Object... args) {
                return requiredType.cast(11L);
            }

            @Override
            public int update(String sql, Object... args) {
                return 1;
            }
        };
        AgentTaskService service = new AgentTaskService(jdbc, new ObjectMapper());

        long id = service.create(
                1L, null, "worker-a", "research", "分析财报",
                "提取关键指标", List.of(3L, 4L));
        int completed = service.complete(id, java.util.Map.of("summary", "ok"));

        assertEquals(11L, id);
        assertEquals(1, completed);
    }

    @Test
    void failureBlocksDownstreamTasksAndRunCancellationIsIdempotent() {
        List<String> sql = new CopyOnWriteArrayList<>();
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public int update(String statement, Object... args) {
                sql.add(statement);
                return 1;
            }
        };
        AgentTaskService service = new AgentTaskService(jdbc, new ObjectMapper());

        assertEquals(1, service.fail(11L, "upstream failed"));
        assertEquals(1, service.cancelRun(7L, "user cancelled"));

        assertEquals(3, sql.size());
        org.junit.jupiter.api.Assertions.assertTrue(sql.get(1).contains("RECURSIVE affected"));
        org.junit.jupiter.api.Assertions.assertTrue(sql.get(2).contains("status = 'CANCELLED'"));
    }
}
