package com.rag2agent.infra.ai.client.impl;

import com.rag2agent.infra.ai.config.AiResilienceProperties;
import com.rag2agent.infra.ai.exception.AiClientException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** AI 无副作用请求的统一可靠性执行器；工具调用不得复用此执行器。 */
final class AiHttpExecutor {

    private enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    private final AiResilienceProperties properties;
    private final MeterRegistry meterRegistry;
    private final Semaphore concurrency;
    private final Object circuitMonitor = new Object();
    private CircuitState circuitState = CircuitState.CLOSED;
    private int consecutiveFailures;
    private long openedAtMillis;
    private boolean halfOpenProbeInFlight;

    AiHttpExecutor(AiResilienceProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.concurrency = new Semaphore(properties.getMaxConcurrentRequests());
    }

    Response execute(OkHttpClient client, Request request, String operation) throws IOException {
        if (!properties.isEnabled()) {
            return executeOnce(client, request);
        }
        beforeCall(operation);
        boolean permit = false;
        try {
            permit = concurrency.tryAcquire();
            if (!permit) {
                resetHalfOpenProbe();
                count(operation, "bulkhead_rejected");
                throw new AiClientException(operation + " 并发请求已达上限",
                        AiClientException.Kind.BULKHEAD_REJECTED, 503, 1);
            }
            return executeWithRetry(client, request, operation);
        } finally {
            if (permit) {
                concurrency.release();
            }
        }
    }

    private Response executeWithRetry(OkHttpClient client, Request request, String operation) throws IOException {
        AiClientException lastFailure = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                Response response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    onSuccess(operation);
                    count(operation, "success");
                    return response;
                }
                String body = response.body() == null ? "" : response.body().string();
                int status = response.code();
                long retryAfter = parseRetryAfter(response.header("Retry-After"));
                response.close();
                AiClientException.Kind kind = status == 429
                        ? AiClientException.Kind.RATE_LIMITED
                        : status >= 500
                                ? AiClientException.Kind.UPSTREAM_ERROR
                                : AiClientException.Kind.CLIENT_ERROR;
                lastFailure = new AiClientException(
                        operation + " API 错误 " + status + ": " + body, kind, status, retryAfter);
                onFailure(operation, kind);
                if (!isRetryable(kind) || attempt == properties.getMaxAttempts() || isCircuitOpen()) {
                    count(operation, outcome(kind));
                    throw lastFailure;
                }
                retry(operation, attempt, retryAfter);
            } catch (IOException exception) {
                lastFailure = new AiClientException(
                        operation + " 调用超时或网络失败: " + exception.getMessage(),
                        exception, AiClientException.Kind.TIMEOUT, 0, 0);
                onFailure(operation, lastFailure.kind());
                if (attempt == properties.getMaxAttempts()) {
                    count(operation, "timeout");
                    throw lastFailure;
                }
                retry(operation, attempt, 0);
            }
        }
        throw lastFailure == null ? new AiClientException(operation + " 调用失败") : lastFailure;
    }

    private Response executeOnce(OkHttpClient client, Request request) throws IOException {
        return client.newCall(request).execute();
    }

    private void beforeCall(String operation) {
        synchronized (circuitMonitor) {
            if (circuitState == CircuitState.OPEN) {
                if (System.currentTimeMillis() - openedAtMillis < properties.getOpenStateMillis()) {
                    count(operation, "circuit_open");
                    throw new AiClientException(operation + " 熔断器已打开，请稍后重试",
                            AiClientException.Kind.CIRCUIT_OPEN, 503, 1);
                }
                circuitState = CircuitState.HALF_OPEN;
                halfOpenProbeInFlight = false;
                countCircuit(operation, "half_open");
            }
            if (circuitState == CircuitState.HALF_OPEN) {
                if (halfOpenProbeInFlight) {
                    count(operation, "circuit_open");
                    throw new AiClientException(operation + " 熔断器正在探测恢复，请稍后重试",
                            AiClientException.Kind.CIRCUIT_OPEN, 503, 1);
                }
                halfOpenProbeInFlight = true;
            }
        }
    }

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

    private void onFailure(String operation, AiClientException.Kind kind) {
        if (!isRetryable(kind)) {
            synchronized (circuitMonitor) {
                if (circuitState == CircuitState.HALF_OPEN) {
                    circuitState = CircuitState.CLOSED;
                    halfOpenProbeInFlight = false;
                    countCircuit(operation, "closed");
                }
            }
            return;
        }
        synchronized (circuitMonitor) {
            consecutiveFailures++;
            if (circuitState == CircuitState.HALF_OPEN
                    || consecutiveFailures >= properties.getFailureThreshold()) {
                circuitState = CircuitState.OPEN;
                openedAtMillis = System.currentTimeMillis();
                halfOpenProbeInFlight = false;
                countCircuit(operation, "open");
            }
        }
    }

    private boolean isRetryable(AiClientException.Kind kind) {
        return kind == AiClientException.Kind.RATE_LIMITED
                || kind == AiClientException.Kind.UPSTREAM_ERROR
                || kind == AiClientException.Kind.TIMEOUT;
    }

    private void retry(String operation, int attempt, long retryAfterSeconds) {
        count(operation, "retry");
        long exponential = properties.getInitialBackoffMillis() * (1L << Math.min(attempt - 1, 10));
        long delay = Math.min(properties.getMaxBackoffMillis(), exponential);
        if (retryAfterSeconds > 0) {
            delay = Math.min(properties.getMaxBackoffMillis(), retryAfterSeconds * 1_000L);
        }
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

    private long parseRetryAfter(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private boolean isCircuitOpen() {
        synchronized (circuitMonitor) {
            return circuitState == CircuitState.OPEN;
        }
    }

    private void resetHalfOpenProbe() {
        synchronized (circuitMonitor) {
            if (circuitState == CircuitState.HALF_OPEN) {
                halfOpenProbeInFlight = false;
            }
        }
    }

    private void count(String operation, String outcome) {
        Counter.builder("rag2agent.ai.requests")
                .tag("operation", operation.toLowerCase())
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    private void countCircuit(String operation, String state) {
        Counter.builder("rag2agent.ai.circuit.transitions")
                .tag("operation", operation.isBlank() ? "unknown" : operation.toLowerCase())
                .tag("state", state)
                .register(meterRegistry)
                .increment();
    }
}
