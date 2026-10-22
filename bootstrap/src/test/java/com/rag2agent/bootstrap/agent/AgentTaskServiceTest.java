package com.rag2agent.bootstrap.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
}
