package com.rag2agent.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.rag2agent")
@MapperScan("com.rag2agent.bootstrap.mapper")
@EnableScheduling
public class Rag2AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(Rag2AgentApplication.class, args);
    }
}
