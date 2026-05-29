package com.rag2agent.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(
        scanBasePackages = "com.rag2agent",
        exclude = DataSourceAutoConfiguration.class)
public class Rag2AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(Rag2AgentApplication.class, args);
    }
}
