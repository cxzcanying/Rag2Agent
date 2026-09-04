package com.rag2agent.bootstrap.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.AgentProperties;
import com.rag2agent.bootstrap.entity.ToolCallRecord;
import com.rag2agent.bootstrap.mapper.ToolCallRecordMapper;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ToolExecutorTest {

    @Test
    void recordsSuccessfulExecutionAndRedactsSensitiveInput() {
        RecordingMapper mapper = new RecordingMapper();
        ThreadPoolTaskExecutor pool = pool();
        try {
            ToolExecutor executor = executor(mapper, pool, tool(arguments -> "ok"), 1000);

            String output = executor.execute(7L, 9L, "test_tool", Map.of("api_key", "credential-value"));

            assertEquals("ok", output);
            assertEquals("SUCCEEDED", mapper.record.getStatus());
            assertEquals("{\"api_key\":\"***\"}", mapper.record.getInput());
            assertFalse(mapper.record.getInput().contains("credential-value"));
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void recordsFailureWithoutPersistingTechnicalDetails() {
        RecordingMapper mapper = new RecordingMapper();
        ThreadPoolTaskExecutor pool = pool();
        try {
            ToolExecutor executor = executor(mapper, pool, tool(arguments -> {
                throw new IllegalArgumentException("upstream credential-value");
            }), 1000);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> executor.execute(7L, 9L, "test_tool", Map.of()));

            assertEquals(ErrorCode.INTERNAL_ERROR, exception.errorCode());
            assertEquals("工具执行失败", mapper.record.getErrorMessage());
            assertFalse(mapper.record.getErrorMessage().contains("credential-value"));
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void timesOutRemoteStyleToolAndRecordsTerminalStatus() {
        RecordingMapper mapper = new RecordingMapper();
        ThreadPoolTaskExecutor pool = pool();
        try {
            ToolExecutor executor = executor(mapper, pool, tool(arguments -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return "late";
            }), 20);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> executor.execute(7L, 9L, "test_tool", Map.of()));

            assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, exception.errorCode());
            assertEquals("TIMED_OUT", mapper.record.getStatus());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void rejectsInvalidArgumentsBeforeSubmittingAndAuditsFailure() {
        RecordingMapper mapper = new RecordingMapper();
        ThreadPoolTaskExecutor pool = pool();
        try {
            Tool tool = new Tool() {
                @Override
                public ToolDescriptor descriptor() {
                    return new ToolDescriptor("test_tool", "测试工具",
                            Map.of("type", "object", "required", List.of("query")), false);
                }

                @Override
                public String execute(Map<String, Object> arguments) {
                    return "never";
                }
            };
            ToolExecutor executor = executor(mapper, pool, tool, 1000);
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> executor.execute(7L, 9L, "test_tool", Map.of()));
            assertEquals(ErrorCode.BAD_REQUEST, exception.errorCode());
            assertEquals("FAILED", mapper.record.getStatus());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void rejectsWhenExecutorQueueIsFull() throws Exception {
        RecordingMapper mapper = new RecordingMapper();
        ThreadPoolTaskExecutor pool = pool();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            ToolExecutor executor = executor(mapper, pool, tool(arguments -> {
                started.countDown();
                try {
                    release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return "ok";
            }), 1000);
            var first = java.util.concurrent.CompletableFuture.runAsync(
                    () -> executor.execute(7L, 9L, "test_tool", Map.of()));
            org.junit.jupiter.api.Assertions.assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS));
            var second = java.util.concurrent.CompletableFuture.runAsync(
                    () -> executor.execute(7L, 9L, "test_tool", Map.of()));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
            while (pool.getThreadPoolExecutor().getQueue().size() < 1
                    && System.nanoTime() < deadline) {
                Thread.yield();
            }
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> executor.execute(7L, 9L, "test_tool", Map.of()));
            assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, exception.errorCode());
            release.countDown();
            first.join();
            second.join();
        } finally {
            pool.getThreadPoolExecutor().shutdownNow();
        }
    }

    private static ToolExecutor executor(
            RecordingMapper mapper,
            ThreadPoolTaskExecutor pool,
            Tool tool,
            long timeoutMillis) {
        AgentProperties properties = new AgentProperties();
        properties.setToolTimeoutMillis(timeoutMillis);
        return new ToolExecutor(
                new ToolRegistry(List.of(tool), Optional.empty()),
                mapper,
                new ObjectMapper(),
                pool,
                properties,
                new SimpleMeterRegistry(),
                ObservationRegistry.create());
    }

    private static ThreadPoolTaskExecutor pool() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(1);
        pool.initialize();
        return pool;
    }

    private static Tool tool(java.util.function.Function<Map<String, Object>, String> action) {
        return new Tool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(
                        "test_tool",
                        "测试工具",
                        Map.of("type", "object", "properties", Map.of()),
                        false);
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                return action.apply(arguments);
            }
        };
    }

    private static final class RecordingMapper implements ToolCallRecordMapper {
        private ToolCallRecord record;

        @Override
        public int insert(ToolCallRecord record) {
            record.setId(1L);
            this.record = record;
            return 1;
        }

        @Override
        public ToolCallRecord selectById(Long id) {
            return record;
        }

        @Override
        public int updateResult(ToolCallRecord record) {
            this.record = record;
            return 1;
        }

        @Override
        public int claimApproval(Long id, Long runId) {
            return 1;
        }

        @Override
        public int timeoutPendingApproval(Long runId, String message) {
            return 1;
        }

        @Override
        public List<ToolCallRecord> listByRunId(Long runId) {
            return record == null ? List.of() : List.of(record);
        }
    }
}
