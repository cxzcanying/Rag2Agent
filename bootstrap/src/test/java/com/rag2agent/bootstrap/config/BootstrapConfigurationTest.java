package com.rag2agent.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class BootstrapConfigurationTest {

    @Test
    void registersQueueDepthAndRemainingCapacityGauges() {
        BootstrapConfiguration configuration = new BootstrapConfiguration();
        ThreadPoolTaskExecutor evaluation = configuration.evaluationTaskExecutor();
        ThreadPoolTaskExecutor retrieval = configuration.retrievalTaskExecutor();
        ThreadPoolTaskExecutor tool = configuration.toolTaskExecutor();
        evaluation.initialize();
        retrieval.initialize();
        tool.initialize();
        try {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            configuration.executorQueueMetrics(evaluation, retrieval, tool).bindTo(registry);
            assertEquals(100, registry.get("rag2agent.executor.queue.capacity")
                    .tag("executor", "evaluation").gauge().value(), 0.1);
            assertEquals(32, registry.get("rag2agent.executor.queue.capacity")
                    .tag("executor", "retrieval").gauge().value(), 0.1);
            assertEquals(0, registry.get("rag2agent.executor.queue.depth")
                    .tag("executor", "tool").gauge().value(), 0.1);
        } finally {
            evaluation.shutdown();
            retrieval.shutdown();
            tool.shutdown();
        }
    }
}
