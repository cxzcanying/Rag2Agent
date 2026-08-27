package com.rag2agent.bootstrap.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.config.AgentProperties;
import com.rag2agent.bootstrap.entity.ToolCallRecord;
import com.rag2agent.bootstrap.mapper.ToolCallRecordMapper;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/** 统一执行工具，并负责 schema、权限、超时、审计和指标。 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);
    private static final int MAX_AUDIT_OUTPUT_CHARS = 16000;

    private final ToolRegistry toolRegistry;
    private final ToolCallRecordMapper toolCallMapper;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final AgentProperties properties;
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;

    public ToolExecutor(
            ToolRegistry toolRegistry,
            ToolCallRecordMapper toolCallMapper,
            ObjectMapper objectMapper,
            @Qualifier("toolTaskExecutor") ThreadPoolTaskExecutor taskExecutor,
            AgentProperties properties,
            MeterRegistry meterRegistry,
            ObservationRegistry observationRegistry) {
        this.toolRegistry = toolRegistry;
        this.toolCallMapper = toolCallMapper;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry;
    }

    public String execute(Long runId, Long userId, String toolName, Map<String, Object> arguments) {
        ToolCallRecord record = new ToolCallRecord();
        record.setRunId(runId);
        record.setToolName(toolName);
        record.setInput(toJson(redact(arguments)));
        record.setStatus("EXECUTING");
        toolCallMapper.insert(record);
        return execute(record, userId, arguments);
    }

    public String execute(ToolCallRecord record, Long userId, Map<String, Object> arguments) {
        String toolName = record.getToolName();
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        Future<String> future = null;
        try {
            Tool tool = toolRegistry.get(toolName);
            if (tool == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "未知工具: " + toolName);
            }
            try {
                toolRegistry.validateArguments(toolName, arguments);
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, exception.getMessage());
            }
            tool.validateAccess(userId, arguments);
            future = taskExecutor.submit(() -> Observation.createNotStarted(
                            "rag2agent.agent.tool", observationRegistry)
                    .lowCardinalityKeyValue("tool", toolName)
                    .observe(() -> tool.execute(arguments)));
            String output = future.get(properties.getToolTimeoutMillis(), TimeUnit.MILLISECONDS);
            record.setStatus("SUCCEEDED");
            record.setOutput(toJson(limitAuditOutput(output)));
            record.setErrorMessage(null);
            persistResult(record);
            return output;
        } catch (TimeoutException exception) {
            outcome = "timeout";
            if (future != null) {
                future.cancel(true);
            }
            record.setStatus("TIMED_OUT");
            record.setErrorMessage("工具执行超时");
            persistResult(record);
            log.warn("工具执行超时: runId={}, tool={}, timeoutMs={}",
                    record.getRunId(), toolName, properties.getToolTimeoutMillis());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "工具执行超时，请稍后重试");
        } catch (RejectedExecutionException exception) {
            outcome = "rejected";
            record.setStatus("FAILED");
            record.setErrorMessage("工具执行队列已满");
            persistResult(record);
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "工具执行繁忙，请稍后重试");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
            record.setStatus("FAILED");
            record.setErrorMessage("工具执行中断");
            persistResult(record);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "工具执行中断");
        } catch (ExecutionException exception) {
            outcome = "error";
            throw fail(record, toolName, exception.getCause());
        } catch (RuntimeException exception) {
            outcome = "error";
            throw fail(record, toolName, exception);
        } finally {
            sample.stop(Timer.builder("rag2agent.agent.tool.duration")
                    .tag("tool", toolName)
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    private RuntimeException fail(ToolCallRecord record, String toolName, Throwable cause) {
        RuntimeException safe;
        if (cause instanceof BusinessException businessException) {
            safe = businessException;
        } else {
            safe = new BusinessException(ErrorCode.INTERNAL_ERROR, "工具执行失败");
        }
        record.setStatus("FAILED");
        record.setErrorMessage(safe.getMessage());
        persistResult(record);
        log.error("工具执行失败: runId={}, tool={}, exceptionType={}",
                record.getRunId(), toolName, cause.getClass().getName());
        return safe;
    }

    private void persistResult(ToolCallRecord record) {
        try {
            toolCallMapper.updateResult(record);
        } catch (RuntimeException exception) {
            // 工具可能已产生副作用，此时不能向上伪装成执行失败并诱发重试。
            log.error("工具审计结果写入失败: runId={}, toolCallId={}",
                    record.getRunId(), record.getId(), exception);
        }
    }

    private String limitAuditOutput(String output) {
        if (output == null || output.length() <= MAX_AUDIT_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_AUDIT_OUTPUT_CHARS) + "...[truncated]";
    }

    private Map<String, Object> redact(Map<String, Object> arguments) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        arguments.forEach((key, value) -> redacted.put(key, isSensitive(key) ? "***" : redactValue(value)));
        return redacted;
    }

    private Object redactValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> redacted = new LinkedHashMap<>();
            nested.forEach((key, item) -> {
                String name = String.valueOf(key);
                redacted.put(name, isSensitive(name) ? "***" : redactValue(item));
            });
            return redacted;
        }
        if (value instanceof List<?> items) {
            return items.stream().map(this::redactValue).toList();
        }
        return value;
    }

    private boolean isSensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "_");
        return normalized.contains("password") || normalized.contains("secret")
                || normalized.contains("token") || normalized.contains("api_key")
                || normalized.contains("authorization");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("工具审计序列化失败", exception);
        }
    }
}
