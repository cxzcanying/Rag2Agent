package com.rag2agent.bootstrap.config;

import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import com.rag2agent.bootstrap.tenancy.TenantAwareDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(Environment env) {
        // 显式声明数据源：从 spring.datasource.* 配置读取（HikariCP）
        HikariDataSource target = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName(env.getProperty("spring.datasource.driver-class-name"))
                .url(env.getProperty("spring.datasource.url"))
                .username(env.getProperty("spring.datasource.username"))
                .password(env.getProperty("spring.datasource.password"))
                .build();
        target.setMaximumPoolSize(intProperty(env, "spring.datasource.hikari.maximum-pool-size", 30));
        target.setMinimumIdle(intProperty(env, "spring.datasource.hikari.minimum-idle", 5));
        target.setConnectionTimeout(longProperty(env, "spring.datasource.hikari.connection-timeout", 5000));
        target.setValidationTimeout(longProperty(env, "spring.datasource.hikari.validation-timeout", 3000));
        target.setIdleTimeout(longProperty(env, "spring.datasource.hikari.idle-timeout", 600000));
        target.setMaxLifetime(longProperty(env, "spring.datasource.hikari.max-lifetime", 1800000));
        target.setConnectionInitSql(env.getProperty(
                "spring.datasource.hikari.connection-init-sql",
                "SET statement_timeout = '30000ms'"));
        return new TenantAwareDataSource(target);
    }

    private int intProperty(Environment env, String key, int fallback) {
        return Integer.parseInt(env.getProperty(key, String.valueOf(fallback)));
    }

    private long longProperty(Environment env, String key, long fallback) {
        return Long.parseLong(env.getProperty(key, String.valueOf(fallback)));
    }
}
