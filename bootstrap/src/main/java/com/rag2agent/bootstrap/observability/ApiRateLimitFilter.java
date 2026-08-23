package com.rag2agent.bootstrap.observability;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.RateLimitProperties;
import com.rag2agent.framework.common.ApiResponse;
import com.rag2agent.framework.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Redis 固定窗口限流，Redis 故障时放行并交给健康检查告警。 */
@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RateLimitProperties properties;

    public ApiRateLimitFilter(
            StringRedisTemplate redis, ObjectMapper objectMapper, RateLimitProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.isEnabled() || !isLimitedPath(request) || !StpUtil.isLogin()) {
            filterChain.doFilter(request, response);
            return;
        }
        String key = "rate-limit:" + StpUtil.getLoginIdAsString() + ":" + (System.currentTimeMillis() / (properties.getWindowSeconds() * 1000L));
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofSeconds(properties.getWindowSeconds()));
            }
            if (count != null && count > properties.getLimit()) {
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(properties.getWindowSeconds()));
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        ApiResponse.failure(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试")));
                return;
            }
        } catch (RuntimeException ignored) {
            // 限流依赖不可用时不阻断主业务，监控应通过 Redis 健康指标发现问题。
        }
        filterChain.doFilter(request, response);
    }

    private boolean isLimitedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith(request.getContextPath() + "/api/")
                && !path.startsWith(request.getContextPath() + "/api/auth/")
                && !path.equals(request.getContextPath() + "/api/health")
                && !path.equals(request.getContextPath() + "/api/version");
    }
}
