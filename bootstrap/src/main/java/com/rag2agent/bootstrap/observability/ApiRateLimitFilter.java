package com.rag2agent.bootstrap.observability;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.RateLimitProperties;
import com.rag2agent.framework.common.ApiResponse;
import com.rag2agent.framework.common.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 已登录 API 的分布式令牌桶；高风险接口在 Redis 故障时使用本地固定窗口兜底。 */
@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final DefaultRedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>(
            "local key = KEYS[1] "
                    + "local rate = tonumber(ARGV[1]) "
                    + "local capacity = tonumber(ARGV[2]) "
                    + "local now = tonumber(ARGV[3]) "
                    + "local ttl = tonumber(ARGV[4]) "
                    + "local tokens = tonumber(redis.call('HGET', key, 'tokens') or capacity) "
                    + "local ts = tonumber(redis.call('HGET', key, 'ts') or now) "
                    + "local current = tokens + (now - ts) * rate / 1000 "
                    + "if current > capacity then current = capacity end "
                    + "if current >= 1 then "
                    + "  redis.call('HSET', key, 'tokens', current - 1, 'ts', now) "
                    + "  redis.call('EXPIRE', key, ttl) "
                    + "  return 0 "
                    + "else "
                    + "  redis.call('HSET', key, 'tokens', current, 'ts', now) "
                    + "  redis.call('EXPIRE', key, ttl) "
                    + "  local wait = (1 - current) * 1000 / rate "
                    + "  if wait < 0 then wait = 0 end "
                    + "  local base = math.floor(wait) "
                    + "  if wait - base > 0 then base = base + 1 end "
                    + "  return base "
                    + "end",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;

    public ApiRateLimitFilter(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            RateLimitProperties properties,
            MeterRegistry meterRegistry) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.isEnabled() || !isLimitedPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean failClosed = isFailClosedPath(request);
        boolean loggedIn;
        try {
            loggedIn = StpUtil.isLogin();
        } catch (RuntimeException exception) {
            if (failClosed) {
                writeFailure(response, 503, ErrorCode.UPSTREAM_UNAVAILABLE,
                        "鉴权服务暂不可用，高风险请求已拒绝，请稍后重试");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }
        if (!loggedIn) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = StpUtil.getLoginIdAsString();
        long waitMillis;
        try {
            waitMillis = tryConsume(
                    "rate-limit:user:" + userId, properties.getLimit(),
                    properties.getFallbackLimit(), failClosed, "user");
            Long tenantId = currentTenantId();
            if (waitMillis == 0 && tenantId != null) {
                waitMillis = tryConsume(
                        "rate-limit:tenant:" + tenantId, properties.getTenantLimit(),
                        properties.getTenantFallbackLimit(), failClosed, "tenant");
            }
            if (waitMillis == 0) {
                waitMillis = tryConsume(
                        "rate-limit:global", properties.getGlobalLimit(),
                        properties.getGlobalFallbackLimit(), failClosed, "global");
            }
        } catch (RateLimitDependencyException exception) {
            writeFailure(response, 503, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "限流服务暂不可用，高风险请求已拒绝，请稍后重试");
            return;
        }
        if (waitMillis > 0) {
            response.setHeader("Retry-After", String.valueOf((waitMillis + 999) / 1000));
            writeFailure(response, 429, ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试");
            return;
        }
        filterChain.doFilter(request, response);
    }

    long tryConsume(String key) {
        return tryConsume(key, false);
    }

    long tryConsume(String key, boolean failClosed) {
        return tryConsume(
                key, properties.getLimit(), properties.getFallbackLimit(), failClosed, "user");
    }

    private long tryConsume(
            String key, int limit, int fallbackLimit, boolean failClosed, String tier) {
        double rate = (double) limit / properties.getWindowSeconds();
        try {
            Long wait = redis.execute(
                    TOKEN_BUCKET,
                    List.of(key),
                    String.valueOf(rate),
                    String.valueOf(limit),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(properties.getWindowSeconds()));
            if (wait == null || wait <= 0) {
                meterRegistry.counter("rag2agent.api.rate_limit",
                        "tier", tier, "outcome", "allowed").increment();
                return 0;
            }
            meterRegistry.counter("rag2agent.api.rate_limit",
                    "tier", tier, "outcome", "rejected").increment();
            return wait;
        } catch (RuntimeException exception) {
            meterRegistry.counter("rag2agent.api.rate_limit",
                    "tier", tier, "outcome", "dependency_error").increment();
            if (failClosed) {
                throw new RateLimitDependencyException(exception);
            }
            return 0;
        }
    }

    boolean isLimitedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith(request.getContextPath() + "/api/")
                && !path.startsWith(request.getContextPath() + "/api/auth/")
                && !path.equals(request.getContextPath() + "/api/health")
                && !path.equals(request.getContextPath() + "/api/version");
    }

    boolean isFailClosedPath(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/api/documents/upload")
                || path.startsWith("/api/chat")
                || path.startsWith("/api/agent/")
                || path.startsWith("/api/search")
                || path.startsWith("/api/ai/");
    }

    private void writeFailure(
            HttpServletResponse response,
            int status,
            ErrorCode errorCode,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.failure(errorCode, message)));
    }

    private Long currentTenantId() {
        try {
            Object value = StpUtil.getSession().get("tenantId");
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                return Long.parseLong(text);
            }
        } catch (RuntimeException ignored) {
            // 老会话没有租户字段时只应用用户与全站限额。
        }
        return null;
    }

    static final class RateLimitDependencyException extends RuntimeException {
        RateLimitDependencyException(Throwable cause) {
            super(cause);
        }
    }
}
