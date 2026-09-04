package com.rag2agent.bootstrap.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.RateLimitProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiRateLimitFilterTest {

    @Test
    void tryConsumeAllowsWhenTokenAvailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RateLimitProperties properties = new RateLimitProperties();
        properties.setLimit(60);
        properties.setWindowSeconds(60);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);
        ApiRateLimitFilter filter = new ApiRateLimitFilter(
                redis, new ObjectMapper(), properties, registry);

        assertEquals(0, filter.tryConsume("rate-limit:1"));
        assertEquals(1.0, registry.get("rag2agent.api.rate_limit").tag("outcome", "allowed").counter().count());
        // 速率 = 60/60 = 1.0 令牌/秒，容量与 TTL 沿用 limit/windowSeconds
        verify(redis).execute(any(RedisScript.class), eq(List.of("rate-limit:1")),
                eq("1.0"), eq("60"), anyString(), eq("60"));
    }

    @Test
    void tryConsumeRejectsAndReturnsWaitMillis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1500L);
        ApiRateLimitFilter filter = new ApiRateLimitFilter(
                redis, new ObjectMapper(), new RateLimitProperties(), registry);

        assertEquals(1500L, filter.tryConsume("rate-limit:1"));
        assertEquals(1.0, registry.get("rag2agent.api.rate_limit").tag("outcome", "rejected").counter().count());
    }

    @Test
    void tryConsumeDegradesOnRedisError() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("redis down"));
        ApiRateLimitFilter filter = new ApiRateLimitFilter(
                redis, new ObjectMapper(), new RateLimitProperties(), registry);

        assertEquals(0, filter.tryConsume("rate-limit:1"));
        assertEquals(1.0, registry.get("rag2agent.api.rate_limit").tag("outcome", "dependency_error").counter().count());
    }

    @Test
    void isLimitedPathExcludesAuthHealthAndVersion() {
        ApiRateLimitFilter filter = new ApiRateLimitFilter(
                mock(StringRedisTemplate.class), new ObjectMapper(), new RateLimitProperties(),
                new SimpleMeterRegistry());

        assertTrue(filter.isLimitedPath(new MockHttpServletRequest("GET", "/api/kb/list")));
        assertTrue(filter.isLimitedPath(new MockHttpServletRequest("GET", "/api/search")));
        assertFalse(filter.isLimitedPath(new MockHttpServletRequest("GET", "/api/auth/login")));
        assertFalse(filter.isLimitedPath(new MockHttpServletRequest("GET", "/api/health")));
        assertFalse(filter.isLimitedPath(new MockHttpServletRequest("GET", "/api/version")));
    }
}
