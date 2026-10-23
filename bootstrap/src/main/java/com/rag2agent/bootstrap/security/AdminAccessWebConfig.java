package com.rag2agent.bootstrap.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AdminAccessWebConfig implements WebMvcConfigurer {

    private final AdminAccessInterceptor interceptor;

    public AdminAccessWebConfig(AdminAccessService accessService) {
        this.interceptor = new AdminAccessInterceptor(accessService);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns(
                        "/api/ai/**",
                        "/api/runtime-config/**",
                        "/actuator/prometheus",
                        "/actuator/metrics/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**")
                .order(2);
    }
}
