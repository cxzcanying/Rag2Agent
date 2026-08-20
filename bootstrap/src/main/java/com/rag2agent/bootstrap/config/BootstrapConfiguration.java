package com.rag2agent.bootstrap.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties({Rag2AgentProperties.class, AgentProperties.class})
public class BootstrapConfiguration {

    @Bean(name = "evaluationTaskExecutor")
    public ThreadPoolTaskExecutor evaluationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("evaluation-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
