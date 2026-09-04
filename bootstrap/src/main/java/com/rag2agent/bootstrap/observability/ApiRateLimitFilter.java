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
 * Redis 令牌桶限流过滤器
 *
 * <p>对已登录用户的 API 请求进行频率控制，基于 Redis 实现分布式限流。
 * 当请求超过配置的阈值时，返回 HTTP 429 (Too Many Requests) 状态码。
 *
 * <p>设计原则：
 * <ul>
 *   <li>限流依赖不可用时，降级放行，不阻断主业务</li>
 *   <li>通过 Micrometer 埋点，由监控系统发现 Redis 健康问题</li>
 *   <li>使用 Lua 脚本把"读桶状态 + 补充令牌 + 消耗令牌"做成原子操作，避免并发超发</li>
 *   <li>令牌桶相比固定窗口能平滑速率：允许受控突发，避免了窗口边界瞬时打满/被限的毛刺</li>
 * </ul>
 *
 * @author 21311
 */
@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {

    /**
     * Lua 脚本：惰性令牌桶（lazy token bucket）
     *
     * <p>KEYS[1]：用户限流桶的 Redis Key（Hash 结构，字段 tokens/ts）
     * <p>ARGV[1]：补充速率（令牌/秒）
     * <p>ARGV[2]：桶容量（最大突发令牌数）
     * <p>ARGV[3]：当前时间戳（毫秒）
     * <p>ARGV[4]：Key 空闲过期时间（秒）
     * <p>返回：0 表示放行；>0 表示被拒，返回"需要等待的毫秒数"
     *
     * <p>惰性补充：不依赖定时任务，每次请求按"距上次时间差 × 速率"补令牌，
     * 因此桶闲置多久都能算出正确的当前令牌数。读改写全程在一个 Lua 中原子完成，
     * 并发请求不会因为"读到同一份旧状态"而超发令牌。
     */
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
     *   <li>生成用户令牌桶的 Redis Key：rate-limit:{userId}</li>
     *   <li>执行令牌桶 Lua：补充令牌并消耗一个，0 放行，>0 返回需等待的毫秒数</li>
     *   <li>若被拒，返回 429 并附带 Retry-After 头</li>
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
        // Key 格式：rate-limit:{userId}
        // 令牌桶状态连续存储在 hash（tokens + 上次补充时间 ts），不需要按窗口编号拆 key。
        String key = "rate-limit:" + StpUtil.getLoginIdAsString();

        // ============ 第三步：令牌桶决策 ============
        // 返回 0 放行；>0 表示被拒，值为需要等待的毫秒数；Redis 异常在 tryConsume 内部降级为放行
        long waitMillis = tryConsume(key);

        // ============ 第四步：被拒则返回 429 ============
        if (waitMillis > 0) {
            // 设置 HTTP 状态码：429 Too Many Requests
            response.setStatus(429);
            // Retry-After 取整到秒（向上取整），告知客户端多久后可重试
            response.setHeader("Retry-After", String.valueOf((waitMillis + 999) / 1000));
            // 返回 JSON 格式的错误信息
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    ApiResponse.failure(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试")));
            return;
        }

        // ============ 第五步：限流通过，正常放行 ============
        // 继续执行后续过滤器链
        filterChain.doFilter(request, response);
    }

    /**
     * 执行令牌桶决策（包内可见，便于单元测试）。
     *
     * <p>参数语义：把配置的 {@code limit} 作为桶容量，{@code limit/windowSeconds} 作为每秒补充速率。
     * 例如 limit=60、windowSeconds=60 → 每秒补 1 个令牌、可突发 60 个，等价于"每分钟 60 次"并平滑了窗口边界。
     *
     * @param key 用户限流桶 Key
     * @return 0 放行；>0 被拒（返回需等待的毫秒数）
     */
    long tryConsume(String key) {
        double rate = (double) properties.getLimit() / properties.getWindowSeconds();
        long now = System.currentTimeMillis();
        try {
            Long wait = redis.execute(
                    TOKEN_BUCKET,
                    List.of(key),
                    String.valueOf(rate),
                    String.valueOf(properties.getLimit()),
                    String.valueOf(now),
                    String.valueOf(properties.getWindowSeconds()));
            // Redis 返回 null（理论上的空结果）时按放行处理，避免误伤
            if (wait == null || wait <= 0) {
                meterRegistry.counter("rag2agent.api.rate_limit", "outcome", "allowed").increment();
                return 0;
            }
            meterRegistry.counter("rag2agent.api.rate_limit", "outcome", "rejected").increment();
            return wait;
        } catch (RuntimeException ignored) {
            // ============ 异常降级：Redis 不可用时不阻断主业务 ============
            // Redis 连接超时、执行异常等情况下，记录 dependency_error 指标，然后放行请求。
            // 具体的 Redis 健康问题由独立的健康检查系统发现并告警。
            meterRegistry.counter("rag2agent.api.rate_limit", "outcome", "dependency_error").increment();
            return 0;
        }
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
    boolean isLimitedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith(request.getContextPath() + "/api/")           // 是 API 路径
                && !path.startsWith(request.getContextPath() + "/api/auth/") // 不是认证接口
                && !path.equals(request.getContextPath() + "/api/health")    // 不是健康检查
                && !path.equals(request.getContextPath() + "/api/version");  // 不是版本查询
    }
}
