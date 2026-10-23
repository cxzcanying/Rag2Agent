package com.rag2agent.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class BootstrapConfigurationTest {

    @Test
    void registersQueueDepthAndRemainingCapacityGauges() {
        BootstrapConfiguration configuration = new BootstrapConfiguration();
        ConcurrencyProperties properties = new ConcurrencyProperties();
        ThreadPoolTaskExecutor evaluation = configuration.evaluationTaskExecutor(properties);
        ThreadPoolTaskExecutor retrieval = configuration.retrievalTaskExecutor(properties);
        ThreadPoolTaskExecutor tool = configuration.toolTaskExecutor(properties);
        ThreadPoolTaskExecutor agentTeam = configuration.agentTeamTaskExecutor(properties);
        ThreadPoolTaskExecutor agentWorker = configuration.agentWorkerTaskExecutor(properties);
        ThreadPoolTaskExecutor memory = configuration.memoryTaskExecutor(properties);
        ThreadPoolTaskExecutor ingest = configuration.ingestTaskExecutor(properties);
        ThreadPoolTaskExecutor docling = configuration.doclingTaskExecutor(properties);
        evaluation.initialize();
        retrieval.initialize();
        tool.initialize();
        agentTeam.initialize();
        agentWorker.initialize();
        memory.initialize();
        ingest.initialize();
        docling.initialize();
        try {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            configuration.executorQueueMetrics(
                    evaluation, retrieval, tool, agentTeam, agentWorker, memory,
                    ingest, docling).bindTo(registry);
            assertEquals(100, registry.get("rag2agent.executor.queue.capacity")
                    .tag("executor", "evaluation").gauge().value(), 0.1);
            assertEquals(32, registry.get("rag2agent.executor.queue.capacity")
                    .tag("executor", "retrieval").gauge().value(), 0.1);
            assertEquals(0, registry.get("rag2agent.executor.queue.depth")
                    .tag("executor", "tool").gauge().value(), 0.1);
            assertEquals(32, registry.get("rag2agent.executor.queue.capacity")
                    .tag("executor", "agent-worker").gauge().value(), 0.1);
            assertEquals(32, registry.get("rag2agent.executor.queue.capacity")
                    .tag("executor", "agent-team").gauge().value(), 0.1);
        } finally {
            evaluation.shutdown();
            retrieval.shutdown();
            tool.shutdown();
            agentTeam.shutdown();
            agentWorker.shutdown();
            memory.shutdown();
            ingest.shutdown();
            docling.shutdown();
        }
    }
}
