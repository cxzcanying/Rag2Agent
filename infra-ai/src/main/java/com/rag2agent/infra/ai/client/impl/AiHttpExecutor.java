package com.rag2agent.infra.ai.client.impl;

import com.rag2agent.infra.ai.config.AiResilienceProperties;
import com.rag2agent.infra.ai.exception.AiClientException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * AI 无副作用请求的统一可靠性执行器
 *
 * <p>本执行器为 AI 客户端提供完整的弹性能力，包括：
 * <ul>
 *   <li><b>熔断器</b>：当失败次数达到阈值时，快速失败避免雪崩</li>
 *   <li><b>重试机制</b>：对可重试的失败进行指数退避重试</li>
 *   <li><b>舱壁隔离</b>：通过信号量限制并发请求数，防止资源耗尽</li>
 *   <li><b>可观测性</b>：完整的 Micrometer 指标埋点</li>
 * </ul>
 *
 * <p><b>重要说明</b>：本执行器仅适用于<strong>无副作用</strong>（幂等）请求。
 * 工具调用（Tool Call）等有副作用的操作<strong>不得复用</strong>此执行器，
 * 因为重试可能导致副作用重复执行。
 *
 * @author 21311
 */
final class AiHttpExecutor {

    /**
     * 熔断器状态枚举
     *
     * <ul>
     *   <li>{@code CLOSED}：关闭状态，正常处理请求</li>
     *   <li>{@code OPEN}：打开状态，直接拒绝请求，快速失败</li>
     *   <li>{@code HALF_OPEN}：半开状态，允许单个探测请求通过，用于检测服务是否恢复</li>
     * </ul>
     */
    private enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    private final AiResilienceProperties properties;   // 弹性配置（重试次数、超时、阈值等）
    private final MeterRegistry meterRegistry;         // Micrometer 指标注册器
    private final Semaphore concurrency;               // 信号量，用于限制最大并发请求数（舱壁隔离）

    // ============ 熔断器状态变量 ============
    // 使用 synchronized + circuitMonitor 保证线程安全
    private final Object circuitMonitor = new Object();
    private CircuitState circuitState = CircuitState.CLOSED;   // 当前熔断器状态
    private int consecutiveFailures;                           // 连续失败次数
    private long openedAtMillis;                               // 熔断器打开时间戳（用于计算恢复等待时间）
    private boolean halfOpenProbeInFlight;                     // 半开状态下是否有探测请求正在执行

    AiHttpExecutor(AiResilienceProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.concurrency = new Semaphore(properties.getMaxConcurrentRequests());
    }

    /**
     * 执行 HTTP 请求（入口方法）
     *
     * <p>执行流程：
     * <ol>
     *   <li>如果弹性功能未启用，直接执行一次（无保护）</li>
     *   <li>调用 beforeCall() 检查熔断器状态</li>
     *   <li>尝试获取信号量许可（舱壁隔离）</li>
     *   <li>获取许可后，执行带重试机制的请求</li>
     *   <li>finally 中释放信号量许可</li>
     * </ol>
     *
     * @param client OkHttp 客户端
     * @param request HTTP 请求
     * @param operation 操作名称（用于日志和指标）
     * @return HTTP 响应（仅当成功时）
     * @throws IOException 网络异常
     * @throws AiClientException 业务异常（限流、熔断、超时等）
     */
    Response execute(OkHttpClient client, Request request, String operation) throws IOException {
        // 弹性功能未启用 → 直接执行，不做任何保护
        if (!properties.isEnabled()) {
            return executeOnce(client, request, operation);
        }

        // 前置检查：熔断器状态
        beforeCall(operation);

        boolean permit = false;
        try {
            // 尝试获取信号量许可（非阻塞）
            // 若获取失败，说明并发数已达上限 → 触发舱壁拒绝
            permit = concurrency.tryAcquire();
            if (!permit) {
                resetHalfOpenProbe();  // 舱壁拒绝时重置半开探测标记
                count(operation, "bulkhead_rejected");
                throw new AiClientException(operation + " 并发请求已达上限",
                        AiClientException.Kind.BULKHEAD_REJECTED, 503, 1);
            }
            // 执行带重试的请求
            return executeWithRetry(client, request, operation);
        } finally {
            // 释放信号量许可（只有获取成功时才释放）
            if (permit) {
                concurrency.release();
            }
        }
    }

    /**
     * 带重试机制的请求执行（核心方法）
     *
     * <p>重试策略：
     * <ul>
     *   <li>可重试的失败类型：RATE_LIMITED、UPSTREAM_ERROR、TIMEOUT</li>
     *   <li>退避算法：指数退避 + 抖动 (Exponential Backoff + Jitter)</li>
     *   <li>最大重试次数：由 properties.getMaxAttempts() 控制</li>
     *   <li>尊重服务端返回的 Retry-After 头</li>
     * </ul>
     *
     * @param client OkHttp 客户端
     * @param request HTTP 请求
     * @param operation 操作名称
     * @return 成功的 HTTP 响应
     * @throws AiClientException 所有重试均失败时抛出
     */
    private Response executeWithRetry(OkHttpClient client, Request request, String operation) throws IOException {
        AiClientException lastFailure = null;

        // 从第 1 次尝试开始，最多尝试 maxAttempts 次
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                // 执行实际的 HTTP 调用
                Response response = client.newCall(request).execute();

                // 响应成功 → 记录成功状态，返回响应
                if (response.isSuccessful()) {
                    onSuccess(operation);
                    count(operation, "success");
                    return response;
                }

                // 响应失败（非 2xx）→ 分类处理
                lastFailure = classifyResponse(response, operation);
                onFailure(operation, lastFailure.kind());

                AiClientException.Kind kind = lastFailure.kind();
                long retryAfter = lastFailure.retryAfterSeconds();

                // 判断是否应该停止重试：
                // 1. 不可重试的失败类型（如 CLIENT_ERROR）
                // 2. 已达到最大重试次数
                // 3. 熔断器已打开
                if (!isRetryable(kind) || attempt == properties.getMaxAttempts() || isCircuitOpen()) {
                    count(operation, outcome(kind));
                    throw lastFailure;
                }

                // 执行重试等待（指数退避 + 抖动）
                retry(operation, attempt, retryAfter);

            } catch (IOException exception) {
                // 网络异常/超时 → 包装为 TIMEOUT 类型
                lastFailure = new AiClientException(
                        operation + " 调用超时或网络失败: " + exception.getMessage(),
                        exception, AiClientException.Kind.TIMEOUT, 0, 0);
                onFailure(operation, lastFailure.kind());

                // 最后一次尝试失败 → 直接抛出
                if (attempt == properties.getMaxAttempts()) {
                    count(operation, "timeout");
                    throw lastFailure;
                }

                // 非最后一次 → 等待后重试
                retry(operation, attempt, 0);
            }
        }

        throw lastFailure == null ? new AiClientException(operation + " 调用失败") : lastFailure;
    }

    /**
     * 无保护的单次执行（弹性功能禁用时使用）
     *
     * <p>直接执行请求，不做任何保护。响应失败时直接抛出异常，不进行重试。
     *
     * @param client OkHttp 客户端
     * @param request HTTP 请求
     * @param operation 操作名称
     * @return 成功的 HTTP 响应
     * @throws IOException 网络异常
     * @throws AiClientException 响应失败时抛出
     */
    private Response executeOnce(OkHttpClient client, Request request, String operation) throws IOException {
        Response response = client.newCall(request).execute();
        if (response.isSuccessful()) {
            return response;
        }
        throw classifyResponse(response, operation);
    }

    /**
     * 分类 HTTP 响应，将其转换为对应的 AiClientException
     *
     * <p>分类规则：
     * <ul>
     *   <li>HTTP 429 → RATE_LIMITED（限流）</li>
     *   <li>HTTP 5xx → UPSTREAM_ERROR（上游服务错误）</li>
     *   <li>HTTP 4xx → CLIENT_ERROR（客户端错误，不可重试）</li>
     * </ul>
     *
     * @param response HTTP 响应
     * @param operation 操作名称
     * @return 对应的 AiClientException
     * @throws IOException 读取响应体时可能抛出
     */
    private AiClientException classifyResponse(Response response, String operation) throws IOException {
        int status = response.code();
        String retryAfterHeader = response.header("Retry-After");
        String body;
        try {
            body = response.body() == null ? "" : response.body().string();
        } finally {
            response.close();  // 确保响应被关闭，释放连接
        }

        long retryAfter = parseRetryAfter(retryAfterHeader);

        // 根据状态码判断失败类型
        AiClientException.Kind kind = status == 429
                ? AiClientException.Kind.RATE_LIMITED
                : status >= 500
                  ? AiClientException.Kind.UPSTREAM_ERROR
                  : AiClientException.Kind.CLIENT_ERROR;

        return new AiClientException(
                operation + " API 错误 " + status + ": " + body, kind, status, retryAfter);
    }

    // ==================== 熔断器核心逻辑 ====================

    /**
     * 请求前置检查：验证熔断器状态
     *
     * <p>状态转换逻辑：
     * <ul>
     *   <li><b>CLOSED</b>：正常通过</li>
     *   <li><b>OPEN</b>：检查是否已达到恢复等待时间
     *       <ul>
     *         <li>未达到 → 拒绝请求，抛出 CIRCUIT_OPEN</li>
     *         <li>已达到 → 切换到 HALF_OPEN</li>
     *       </ul>
     *   </li>
     *   <li><b>HALF_OPEN</b>：检查是否有探测请求正在执行
     *       <ul>
     *         <li>有 → 拒绝（防止并发探测）</li>
     *         <li>无 → 标记探测请求，允许通过</li>
     *       </ul>
     *   </li>
     * </ul>
     */
    private void beforeCall(String operation) {
        synchronized (circuitMonitor) {
            if (circuitState == CircuitState.OPEN) {
                // 检查是否已达到恢复等待时间
                if (System.currentTimeMillis() - openedAtMillis < properties.getOpenStateMillis()) {
                    count(operation, "circuit_open");
                    throw new AiClientException(operation + " 熔断器已打开，请稍后重试",
                            AiClientException.Kind.CIRCUIT_OPEN, 503, 1);
                }
                // 等待时间已到 → 进入半开状态，尝试探测
                circuitState = CircuitState.HALF_OPEN;
                halfOpenProbeInFlight = false;
                countCircuit(operation, "half_open");
            }

            // 半开状态下的并发控制：同时只允许一个探测请求
            if (circuitState == CircuitState.HALF_OPEN) {
                if (halfOpenProbeInFlight) {
                    count(operation, "circuit_open");
                    throw new AiClientException(operation + " 熔断器正在探测恢复，请稍后重试",
                            AiClientException.Kind.CIRCUIT_OPEN, 503, 1);
                }
                halfOpenProbeInFlight = true;  // 标记探测请求
            }
        }
    }

    /**
     * 请求成功回调：重置熔断器
     *
     * <p>成功时：
     * <ul>
     *   <li>重置连续失败计数器为 0</li>
     *   <li>如果当前不是 CLOSED 状态，切换为 CLOSED（表示服务已恢复）</li>
     * </ul>
     */
    private void onSuccess(String operation) {
        synchronized (circuitMonitor) {
            consecutiveFailures = 0;
            if (circuitState != CircuitState.CLOSED) {
                circuitState = CircuitState.CLOSED;
                halfOpenProbeInFlight = false;
                countCircuit(operation, "closed");
            }
        }
    }

    /**
     * 请求失败回调：更新熔断器状态
     *
     * <p>失败处理逻辑：
     * <ul>
     *   <li><b>不可重试的失败</b>（如 CLIENT_ERROR）：
     *       <ul>
     *         <li>如果是 HALF_OPEN 状态 → 关闭熔断器（因为探测到不可恢复的错误）</li>
     *         <li>不累加连续失败计数</li>
     *       </ul>
     *   </li>
     *   <li><b>可重试的失败</b>（如 TIMEOUT、RATE_LIMITED）：
     *       <ul>
     *         <li>累加连续失败计数</li>
     *         <li>如果达到失败阈值 → 打开熔断器</li>
     *         <li>如果在 HALF_OPEN 状态下失败 → 立即打开熔断器</li>
     *       </ul>
     *   </li>
     * </ul>
     */
    private void onFailure(String operation, AiClientException.Kind kind) {
        // 不可重试的错误 → 不触发熔断，但半开状态下探测失败时关闭熔断器
        if (!isRetryable(kind)) {
            synchronized (circuitMonitor) {
                if (circuitState == CircuitState.HALF_OPEN) {
                    circuitState = CircuitState.CLOSED;      // 探测失败，回到关闭状态
                    halfOpenProbeInFlight = false;
                    countCircuit(operation, "closed");
                }
            }
            return;
        }

        // 可重试的错误 → 累加失败次数，判断是否打开熔断器
        synchronized (circuitMonitor) {
            consecutiveFailures++;
            // 触发熔断的条件：
            // 1. 半开状态下失败 → 立即熔断
            // 2. 连续失败次数达到阈值 → 熔断
            if (circuitState == CircuitState.HALF_OPEN
                    || consecutiveFailures >= properties.getFailureThreshold()) {
                circuitState = CircuitState.OPEN;
                openedAtMillis = System.currentTimeMillis();
                halfOpenProbeInFlight = false;
                countCircuit(operation, "open");
            }
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 判断失败类型是否可重试
     *
     * <p>可重试类型：
     * <ul>
     *   <li>RATE_LIMITED：限流，等待后可能恢复</li>
     *   <li>UPSTREAM_ERROR：上游服务错误（5xx），可能是临时问题</li>
     *   <li>TIMEOUT：超时，可能是网络波动</li>
     * </ul>
     *
     * <p>不可重试类型：
     * <ul>
     *   <li>CLIENT_ERROR：客户端错误（4xx），重试无意义，应修正请求</li>
     *   <li>CIRCUIT_OPEN：熔断打开，不应在循环内重试</li>
     *   <li>BULKHEAD_REJECTED：舱壁拒绝，重试无意义</li>
     * </ul>
     */
    private boolean isRetryable(AiClientException.Kind kind) {
        return kind == AiClientException.Kind.RATE_LIMITED
                || kind == AiClientException.Kind.UPSTREAM_ERROR
                || kind == AiClientException.Kind.TIMEOUT;
    }

    /**
     * 执行重试等待（指数退避 + 抖动）
     *
     * <p>退避算法：
     * <pre>
     *   delay = min(maxBackoff, initialBackoff * 2^(attempt-1))
     *   加入 ±jitter 抖动，避免"惊群效应"
     *   如果服务端返回 Retry-After，优先使用该值
     * </pre>
     *
     * @param operation 操作名称
     * @param attempt 当前尝试次数（从 1 开始）
     * @param retryAfterSeconds 服务端建议的重试等待秒数（0 表示无建议）
     */
    private void retry(String operation, int attempt, long retryAfterSeconds) {
        count(operation, "retry");

        // 指数退避：initialBackoff * 2^(attempt-1)，限制最大位移 10 位防止溢出
        long exponential = properties.getInitialBackoffMillis() * (1L << Math.min(attempt - 1, 10));
        long delay = Math.min(properties.getMaxBackoffMillis(), exponential);

        // 如果服务端返回了 Retry-After，优先使用（但不能超过最大退避时间）
        if (retryAfterSeconds > 0) {
            delay = Math.min(properties.getMaxBackoffMillis(), retryAfterSeconds * 1_000L);
        }

        // 添加抖动（Jitter）：在 [-jitter, +jitter] 范围内随机偏移
        // 避免大量客户端同时重试造成"重试风暴"
        long jitter = (long) (delay * properties.getJitterRatio());
        if (jitter > 0) {
            delay = Math.max(0, delay + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1));
        }

        try {
            TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiClientException(operation + " 重试被中断", exception,
                    AiClientException.Kind.TIMEOUT, 0, 0);
        }
    }

    /**
     * 将失败类型转换为指标标签值
     */
    private String outcome(AiClientException.Kind kind) {
        return switch (kind) {
            case RATE_LIMITED -> "rate_limited";
            case UPSTREAM_ERROR -> "upstream_error";
            case TIMEOUT -> "timeout";
            case CLIENT_ERROR -> "client_error";
            case CIRCUIT_OPEN -> "circuit_open";
            case BULKHEAD_REJECTED -> "bulkhead_rejected";
        };
    }

    /**
     * 解析 Retry-After 响应头
     *
     * <p>支持两种格式：
     * <ul>
     *   <li>数字秒数：如 "30" → 30 秒后重试</li>
     *   <li>HTTP 日期：如 "Wed, 21 Oct 2015 07:28:00 GMT" → 该时间后重试</li>
     * </ul>
     *
     * @param value Retry-After 头值
     * @return 建议等待秒数，范围 [0, 86400]（最多 1 天）
     */
    private long parseRetryAfter(String value) {
        if (value == null) {
            return 0;
        }

        String normalized = value.trim();

        // 尝试解析为数字秒数
        try {
            return Math.clamp(Long.parseLong(normalized), 0, TimeUnit.DAYS.toSeconds(1));
        } catch (NumberFormatException ignored) {
            // 解析数字失败 → 尝试解析为日期
            try {
                Instant retryAt = ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                long delayMillis = Duration.between(Instant.now(), retryAt).toMillis();
                return Math.clamp((delayMillis + 999) / 1_000, 0, TimeUnit.DAYS.toSeconds(1));
            } catch (DateTimeParseException ignoredDate) {
                return 0;  // 格式无法识别 → 忽略
            }
        }
    }

    /**
     * 检查熔断器是否为 OPEN 状态
     */
    private boolean isCircuitOpen() {
        synchronized (circuitMonitor) {
            return circuitState == CircuitState.OPEN;
        }
    }

    /**
     * 重置半开探测标记
     *
     * <p>当请求被舱壁拒绝时调用，防止半开状态下探测请求被阻塞导致
     * 探测标记无法清除，使熔断器永久停留在半开状态。
     */
    private void resetHalfOpenProbe() {
        synchronized (circuitMonitor) {
            if (circuitState == CircuitState.HALF_OPEN) {
                halfOpenProbeInFlight = false;
            }
        }
    }

    // ==================== 指标埋点 ====================

    /**
     * 记录 AI 请求指标
     *
     * <p>指标名：rag2agent.ai.requests
     * <p>标签：
     * <ul>
     *   <li>operation：操作名称</li>
     *   <li>outcome：结果（success、retry、timeout、rate_limited 等）</li>
     * </ul>
     */
    private void count(String operation, String outcome) {
        Counter.builder("rag2agent.ai.requests")
                .tag("operation", normalizeOperation(operation))
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录熔断器状态转换指标
     *
     * <p>指标名：rag2agent.ai.circuit.transitions
     * <p>标签：
     * <ul>
     *   <li>operation：操作名称</li>
     *   <li>state：目标状态（closed、open、half_open）</li>
     * </ul>
     */
    private void countCircuit(String operation, String state) {
        Counter.builder("rag2agent.ai.circuit.transitions")
                .tag("operation", normalizeOperation(operation))
                .tag("state", state)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 规范化操作名称（用于指标标签）
     */
    private String normalizeOperation(String operation) {
        return operation == null || operation.isBlank() ? "unknown" : operation.toLowerCase(Locale.ROOT);
    }
}