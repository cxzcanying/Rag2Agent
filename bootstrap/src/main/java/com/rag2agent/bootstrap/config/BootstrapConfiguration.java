package com.rag2agent.bootstrap.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
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
})
public class BootstrapConfiguration {

    /**
     * 单线程评测，控制评测并发和模型消耗
     * @return 专门用于执行评测任务的线程池
     */
    @Bean(name = "evaluationTaskExecutor")
    public ThreadPoolTaskExecutor evaluationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        //单线程
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("evaluation-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    /** 受控检索并行池，避免向量和关键词召回占用 common pool。 */
    @Bean(name = "retrievalTaskExecutor")
    public ThreadPoolTaskExecutor retrievalTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("retrieval-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    /** 工具执行使用独立有界线程池，远程工具阻塞时不会占满请求线程。 */
    @Bean(name = "toolTaskExecutor")
    public ThreadPoolTaskExecutor toolTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("tool-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Bean
    public MeterBinder executorQueueMetrics(
            @Qualifier("evaluationTaskExecutor") ThreadPoolTaskExecutor evaluation,
            @Qualifier("retrievalTaskExecutor") ThreadPoolTaskExecutor retrieval,
            @Qualifier("toolTaskExecutor") ThreadPoolTaskExecutor tool) {
        return registry -> {
            registerQueueGauge(registry, "evaluation", evaluation);
            registerQueueGauge(registry, "retrieval", retrieval);
            registerQueueGauge(registry, "tool", tool);
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
