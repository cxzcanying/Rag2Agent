package com.rag2agent.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rag2agent.bootstrap.config.TokenCostProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TokenCostServiceTest {

    @Test
    void usesProviderCreatedTimeForPeakPricingAndPersistsRequestContext() {
        TokenCostProperties properties = new TokenCostProperties();
        TokenCostProperties.Price price = new TokenCostProperties.Price();
        price.setVersion("test");
        price.setCurrency("USD");
        price.setPromptPerMillion(0.15);
        price.setCompletionPerMillion(0.60);
        price.setCacheReadPerMillion(0.003);
        price.setPeakMultiplier(2);
        price.setPeakWeekdaysOnly(true);
        price.setPeakWindowsUtc("01:00-04:00,06:00-10:00");
        properties.getPrices().put("deepseek/test", price);
        CapturingJdbc jdbc = new CapturingJdbc();
        TokenCostService service =
                new TokenCostService(properties, new SimpleMeterRegistry(), jdbc);
        Instant startedAt = Instant.parse("2026-10-19T02:00:00Z");
        Instant providerCreatedAt = Instant.parse("2026-10-19T05:00:00Z");
        var context = new TokenCostService.CostContext(
                "request-1", 2L, 3L, 4L, 5L, "agent.chat", startedAt);
        Map<String, Object> usage = Map.of(
                "prompt_tokens", 1_000_000L,
                "completion_tokens", 100_000L,
                "total_tokens", 1_100_000L,
                "prompt_tokens_details", Map.of("cached_tokens", 200_000L),
                "created", providerCreatedAt.getEpochSecond());

        service.record(context, usage, List.of(), List.of(), "deepseek", "test");

        Object[] values = jdbc.values.getFirst();
        assertEquals("request-1", values[12]);
        assertEquals(2L, values[13]);
        assertEquals(3L, values[14]);
        assertEquals(4L, values[15]);
        assertEquals(5L, values[16]);
        assertEquals("agent.chat", values[17]);
        assertEquals(java.sql.Timestamp.from(startedAt), values[18]);
        assertEquals(java.sql.Timestamp.from(providerCreatedAt), values[19]);
        assertEquals("off-peak", values[26]);
        assertEquals("SUCCESS", values[27]);
        assertEquals(0.1806, (double) values[9], 0.0000001);
    }

    private static final class CapturingJdbc extends JdbcTemplate {
        private final List<Object[]> values = new ArrayList<>();

        @Override
        public int update(String sql, Object... args) {
            values.add(args);
            return 1;
        }
    }
}
