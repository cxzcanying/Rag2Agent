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
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Redis 固定窗口限流过滤器
 *
 * <p>对已登录用户的 API 请求进行频率控制，基于 Redis 实现分布式限流。
 * 当请求超过配置的阈值时，返回 HTTP 429 (Too Many Requests) 状态码。
 *
 * <p>设计原则：
 * <ul>
 *   <li>限流依赖不可用时，降级放行，不阻断主业务</li>
 *   <li>通过 Micrometer 埋点，由监控系统发现 Redis 健康问题</li>
 *   <li>使用 Lua 脚本保证 INCR + EXPIRE 的原子性</li>
 * </ul>
 *
 * @author 21311
 */
@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {

    /**
     * Lua 脚本：原子性地递增计数器并设置过期时间
     *
     * <p>KEYS[1]：限流计数器的 Redis Key
     * <p>ARGV[1]：窗口过期时间（秒）
     * <p>返回：当前窗口内的请求计数
     *
     * <p>使用 Lua 脚本而非多条 Redis 命令的原因：
     * 保证 INCR 和 EXPIRE 的原子性，避免并发时 TTL 未设置导致的 Key 永不过期问题。
     */
    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]) "                    // 原子递增计数器
                    + "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "  // 首次创建时设置过期时间
                    + "return count",
            Long.class);

    private final StringRedisTemplate redis;           // Redis 客户端，用于执行限流操作
    private final ObjectMapper objectMapper;           // JSON 序列化工具，用于返回限流错误信息
    private final RateLimitProperties properties;      // 限流配置（启用开关、窗口时长、次数上限）
    private final MeterRegistry meterRegistry;         // Micrometer 指标注册器，用于监控埋点

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

    /**
     * 核心过滤逻辑
     *
     * <p>执行流程：
     * <ol>
     *   <li>检查限流功能是否启用、路径是否需要限流、用户是否已登录，任一不满足则直接放行</li>
     *   <li>生成当前时间窗口的 Redis Key：rate-limit:{userId}:{windowNumber}</li>
     *   <li>执行 Lua 脚本递增计数并获取当前窗口内的请求总数</li>
     *   <li>若计数超过阈值，返回 429 并附带 Retry-After 头</li>
     *   <li>若 Redis 执行异常，降级放行并记录 dependency_error 指标</li>
     *   <li>正常通过时，记录 allowed 指标并放行</li>
     * </ol>
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        // ============ 第一步：条件检查 ============
        // 限流未启用、路径不在限流白名单、用户未登录 → 直接放行
        if (!properties.isEnabled() || !isLimitedPath(request) || !StpUtil.isLogin()) {
            filterChain.doFilter(request, response);
            return;
        }

        // ============ 第二步：生成 Redis Key ============
        // Key 格式：rate-limit:{userId}:{当前时间窗口编号}
        // 窗口编号 = 当前时间戳 / 窗口秒数，确保同一窗口内所有请求共享同一个 Key
        String key = "rate-limit:" + StpUtil.getLoginIdAsString() + ":"
                + (System.currentTimeMillis() / (properties.getWindowSeconds() * 1000L));

        try {
            // ============ 第三步：执行限流计数 ============
            // 执行 Lua 脚本，原子性地递增计数器并获取当前值
            Long count = redis.execute(
                    INCREMENT_WITH_TTL,
                    List.of(key),
                    String.valueOf(properties.getWindowSeconds()));

            // ============ 第四步：判断是否超限 ============
            if (count != null && count > properties.getLimit()) {
                // 超出限流阈值 → 返回 429

                // 设置 HTTP 状态码：429 Too Many Requests
                response.setStatus(429);

                // 记录被拒绝的指标，用于监控限流命中率
                meterRegistry.counter("rag2agent.api.rate_limit", "outcome", "rejected").increment();

                // 设置 Retry-After 响应头，告知客户端等待多久后重试（单位：秒）
                response.setHeader("Retry-After", String.valueOf(properties.getWindowSeconds()));

                // 返回 JSON 格式的错误信息
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        ApiResponse.failure(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试")));
                return;
            }
        } catch (RuntimeException ignored) {
            // ============ 异常降级：Redis 不可用时不阻断主业务 ============
            // Redis 连接超时、执行异常等情况下，记录 dependency_error 指标，
            // 然后放行请求。具体的 Redis 健康问题由独立的健康检查系统发现并告警。
            meterRegistry.counter("rag2agent.api.rate_limit", "outcome", "dependency_error").increment();
            // 注意：此处不重新抛出异常，也不返回错误响应，保证业务可用性优先
        }

        // ============ 第五步：限流通过，正常放行 ============
        // 记录允许通过的指标
        meterRegistry.counter("rag2agent.api.rate_limit", "outcome", "allowed").increment();

        // 继续执行后续过滤器链
        filterChain.doFilter(request, response);
    }

    /**
     * 判断当前请求路径是否需要进行限流
     *
     * <p>限流规则：
     * <ul>
     *   <li>✅ 必须以 /api/ 开头 — 只针对 API 接口限流</li>
     *   <li>❌ 不以 /api/auth/ 开头 — 认证接口（登录、注册等）不限流，避免影响用户登录</li>
     *   <li>❌ 不等于 /api/health — 健康检查不限流，保证运维探活正常</li>
     *   <li>❌ 不等于 /api/version — 版本查询不限流，保证服务发现正常</li>
     * </ul>
     *
     * @param request HTTP 请求对象
     * @return true 表示需要限流，false 表示不需要
     */
    private boolean isLimitedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith(request.getContextPath() + "/api/")           // 是 API 路径
                && !path.startsWith(request.getContextPath() + "/api/auth/") // 不是认证接口
                && !path.equals(request.getContextPath() + "/api/health")    // 不是健康检查
                && !path.equals(request.getContextPath() + "/api/version");  // 不是版本查询
    }
}