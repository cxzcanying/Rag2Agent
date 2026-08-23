package com.rag2agent.bootstrap.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 把当前 HTTP span 的标识放入 MDC，交给 Spring Boot JSON 日志统一输出。
 * 只记录标识，不把 prompt、文档内容和密钥写入日志。
 * @author 21311
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TraceMdcFilter extends OncePerRequestFilter {

    private final Tracer tracer;

    public TraceMdcFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Span span = tracer.currentSpan();
        if (span == null) {
            filterChain.doFilter(request, response);
            return;
        }
        MDC.put("traceId", span.context().traceId());
        MDC.put("spanId", span.context().spanId());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            MDC.remove("spanId");
        }
    }
}
