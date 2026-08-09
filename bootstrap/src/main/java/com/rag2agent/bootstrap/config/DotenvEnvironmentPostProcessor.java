package com.rag2agent.bootstrap.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 本地开发加载工作目录下的 .env 文件（系统环境变量优先，.env 只补缺）。
 * .env 已被 .gitignore 忽略，密钥不会进入仓库。
 * @author 21311
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path dotenv = findDotenv(Path.of(System.getProperty("user.dir")));
        if (!Files.exists(dotenv)) {
            return;
        }
        Properties properties = new Properties();
        try {
            properties.load(Files.newBufferedReader(dotenv, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("加载 .env 失败: " + dotenv, e);
        }
        properties.forEach((key, value) -> {
            String name = key.toString();
            if (!environment.containsProperty(name)) {
                environment.getSystemProperties().put(name, value);
            }
        });
    }

    /**
     * 从指定目录向上逐级查找 .env（兼容不同工作目录启动）。
     * 跑测试时工作目录是模块目录bootstrap，不是项目根目录
     */
    private static Path findDotenv(Path start) {
        Path current = start.toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(".env");
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return Path.of(".env");
    }
}
