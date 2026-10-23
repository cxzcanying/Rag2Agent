package com.rag2agent.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.rag2agent.infra.ai.client.EmbeddingClient;
import com.rag2agent.bootstrap.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AgentMemoryServiceTest {

    @Test
    void deletesOnlyOwnedMemory() {
        TenantContext.set(1);
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public int update(String sql, Object... args) {
                return 1;
            }
        };
        AgentMemoryService service = new AgentMemoryService(jdbc, mock(EmbeddingClient.class));

        assertEquals(1, service.forget(2L, 9L));
        TenantContext.clear();
    }
}
