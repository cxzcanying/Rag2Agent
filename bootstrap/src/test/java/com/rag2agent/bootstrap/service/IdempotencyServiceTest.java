package com.rag2agent.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.framework.exception.BusinessException;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class IdempotencyServiceTest {

    @Test
    void duplicateRequestReplaysCompletedResponse() {
        FakeJdbc jdbc = new FakeJdbc();
        IdempotencyService service = new IdempotencyService(jdbc, new ObjectMapper());

        assertNull(service.reserve(7L, "scope", "key", Map.of("value", 1)));
        service.complete(7L, "scope", "key", Map.of("id", 42));

        assertEquals("{\"id\":42}", service.reserve(7L, "scope", "key", Map.of("value", 1)));
    }

    @Test
    void inProgressRequestIsRejectedAndDifferentPayloadIsInvalid() {
        FakeJdbc jdbc = new FakeJdbc();
        IdempotencyService service = new IdempotencyService(jdbc, new ObjectMapper());
        service.reserve(7L, "scope", "key", Map.of("value", 1));

        assertThrows(BusinessException.class,
                () -> service.reserve(7L, "scope", "key", Map.of("value", 1)));
        assertThrows(BusinessException.class,
                () -> service.reserve(7L, "scope", "key", Map.of("value", 2)));
    }

    @Test
    void concurrentInsertCreatesOneReservation() throws Exception {
        FakeJdbc jdbc = new FakeJdbc();
        IdempotencyService service = new IdempotencyService(jdbc, new ObjectMapper());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            Callable<String> call = () -> {
                try {
                    return service.reserve(7L, "scope", "key", Map.of("value", 1));
                } catch (BusinessException expected) {
                    return "rejected";
                }
            };
            var futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> executor.submit(call)).toList();
            long reserved = 0;
            for (Future<String> future : futures) {
                if (future.get() == null) reserved++;
            }
            assertEquals(1, reserved);
            assertEquals(1, jdbc.insertions.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class FakeJdbc extends JdbcTemplate {
        private final Map<String, Row> rows = new ConcurrentHashMap<>();
        private final AtomicInteger insertions = new AtomicInteger();

        @Override
        public int update(String sql, Object... args) {
            if (sql.startsWith("DELETE FROM")) return 0;
            if (sql.startsWith("INSERT INTO")) {
                String key = args[0] + "|" + args[1] + "|" + args[2];
                synchronized (rows) {
                    if (rows.containsKey(key)) throw new DuplicateKeyException("duplicate");
                    rows.put(key, new Row((String) args[3], null));
                    insertions.incrementAndGet();
                }
                return 1;
            }
            if (sql.startsWith("UPDATE")) {
                String key = args[1] + "|" + args[2] + "|" + args[3];
                rows.computeIfPresent(key, (ignored, row) -> new Row(row.hash(), (String) args[0]));
                return 1;
            }
            return 0;
        }

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            String key = args[0] + "|" + args[1] + "|" + args[2];
            Row row = rows.get(key);
            Map<String, Object> result = new HashMap<>();
            result.put("request_hash", row.hash());
            result.put("response_json", row.response());
            return result;
        }

        private record Row(String hash, String response) {}
    }
}
