package com.rag2agent.bootstrap.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(Rag2AgentProperties.class)
public class BootstrapConfiguration {}
