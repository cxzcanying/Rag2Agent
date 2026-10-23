package com.rag2agent.bootstrap.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.rag2agent.bootstrap.tenancy.TenantAwareTaskDecorator;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
@EnableConfigurationProperties({
    Rag2AgentProperties.class,
    AgentProperties.class,
    RateLimitProperties.class,
    EmbeddingCacheProperties.class,
    RetrievalProperties.class,
    TokenCostProperties.class
    , McpRemoteProperties.class
    , RecoveryProperties.class
    , Neo4jGraphProperties.class
    , TenantProperties.class
    , RegistrationProperties.class
    , SecurityProperties.class
    , ConcurrencyProperties.class
    , IngestProperties.class
    , LangfuseProperties.class
    , MemoryExtractionProperties.class
})
public class BootstrapConfiguration {

    /**
     * 单线程评测，控制评测并发和模型消耗
     * @return 专门用于执行评测任务的线程池
     */
    @Bean(name = "evaluationTaskExecutor")
    public ThreadPoolTaskExecutor evaluationTaskExecutor(ConcurrencyProperties properties) {
        return executor("evaluation-", properties.getEvaluation(), false);
    }

    /** 受控检索并行池，避免向量和关键词召回占用 common pool。 */
    @Bean(name = "retrievalTaskExecutor")
    public ThreadPoolTaskExecutor retrievalTaskExecutor(ConcurrencyProperties properties) {
        return executor("retrieval-", properties.getRetrieval(), true);
    }

    /** 工具执行使用独立有界线程池，远程工具阻塞时不会占满请求线程。 */
    @Bean(name = "toolTaskExecutor")
    public ThreadPoolTaskExecutor toolTaskExecutor(ConcurrencyProperties properties) {
        return executor("tool-", properties.getTool(), true);
    }

    /** 团队编排只占用独立线程，不能与它等待的 Worker 共用线程池。 */
    @Bean(name = "agentTeamTaskExecutor")
    public ThreadPoolTaskExecutor agentTeamTaskExecutor(ConcurrencyProperties properties) {
        return executor("agent-team-", properties.getAgentTeam(), true);
    }

    /** 多 Agent Worker 使用独立有界线程池，隔离分析并发与检索、工具调用。 */
    @Bean(name = "agentWorkerTaskExecutor")
    public ThreadPoolTaskExecutor agentWorkerTaskExecutor(ConcurrencyProperties properties) {
        return executor("agent-worker-", properties.getAgentWorker(), true);
    }

    @Bean(name = "memoryTaskExecutor")
    public ThreadPoolTaskExecutor memoryTaskExecutor(ConcurrencyProperties properties) {
        return executor("memory-", properties.getMemory(), true);
    }

    @Bean(name = "ingestTaskExecutor")
    public ThreadPoolTaskExecutor ingestTaskExecutor(ConcurrencyProperties properties) {
        return executor("ingest-", properties.getIngest(), true);
    }

    @Bean(name = "doclingTaskExecutor")
    public ThreadPoolTaskExecutor doclingTaskExecutor(ConcurrencyProperties properties) {
        return executor("docling-", properties.getDocling(), true);
    }

    private ThreadPoolTaskExecutor executor(
            String prefix, ConcurrencyProperties.Pool pool, boolean awaitShutdown) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.getCoreSize());
        executor.setMaxPoolSize(pool.getMaxSize());
        executor.setQueueCapacity(pool.getQueueCapacity());
        executor.setThreadNamePrefix(prefix);
        executor.setTaskDecorator(new TenantAwareTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(awaitShutdown);
        if (awaitShutdown) {
            executor.setAwaitTerminationSeconds(10);
        }
        return executor;
    }

    @Bean
    public MeterBinder executorQueueMetrics(
            @Qualifier("evaluationTaskExecutor") ThreadPoolTaskExecutor evaluation,
            @Qualifier("retrievalTaskExecutor") ThreadPoolTaskExecutor retrieval,
            @Qualifier("toolTaskExecutor") ThreadPoolTaskExecutor tool,
            @Qualifier("agentTeamTaskExecutor") ThreadPoolTaskExecutor agentTeam,
            @Qualifier("agentWorkerTaskExecutor") ThreadPoolTaskExecutor agentWorker,
            @Qualifier("memoryTaskExecutor") ThreadPoolTaskExecutor memory,
            @Qualifier("ingestTaskExecutor") ThreadPoolTaskExecutor ingest,
            @Qualifier("doclingTaskExecutor") ThreadPoolTaskExecutor docling) {
        return registry -> {
            registerQueueGauge(registry, "evaluation", evaluation);
            registerQueueGauge(registry, "retrieval", retrieval);
            registerQueueGauge(registry, "tool", tool);
            registerQueueGauge(registry, "agent-team", agentTeam);
            registerQueueGauge(registry, "agent-worker", agentWorker);
            registerQueueGauge(registry, "memory", memory);
            registerQueueGauge(registry, "ingest", ingest);
            registerQueueGauge(registry, "docling", docling);
        };
    }

    private void registerQueueGauge(
            io.micrometer.core.instrument.MeterRegistry registry,
            String name, ThreadPoolTaskExecutor executor) {
        registry.gauge("rag2agent.executor.queue.depth",
                io.micrometer.core.instrument.Tags.of("executor", name),
                executor, value -> value.getThreadPoolExecutor().getQueue().size());
        registry.gauge("rag2agent.executor.queue.capacity",
                io.micrometer.core.instrument.Tags.of("executor", name),
                executor, value -> value.getThreadPoolExecutor().getQueue().remainingCapacity());
    }
}
