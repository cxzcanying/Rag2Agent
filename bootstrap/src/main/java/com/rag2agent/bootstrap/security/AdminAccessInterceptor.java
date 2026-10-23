package com.rag2agent.bootstrap.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class AdminAccessInterceptor implements HandlerInterceptor {

    private final AdminAccessService accessService;

    public AdminAccessInterceptor(AdminAccessService accessService) {
        this.accessService = accessService;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        accessService.requireAccess(request, allowsInternal(request.getRequestURI()));
        return true;
    }

    private boolean allowsInternal(String path) {
        return path.startsWith("/actuator/prometheus")
                || path.startsWith("/actuator/metrics")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/api/ai/");
    }
}
