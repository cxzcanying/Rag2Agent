package com.rag2agent.bootstrap.config;

import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 单实例运行时配置快照；重启后回到 YAML，适合开发和单机演示。 */
@Service
public class RuntimeConfigService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigService.class);

    private final RateLimitProperties rateLimit;
    private final RetrievalProperties retrieval;
    private final AgentProperties agent;
    private final AtomicLong version = new AtomicLong(0);

    public RuntimeConfigService(RateLimitProperties rateLimit, RetrievalProperties retrieval, AgentProperties agent) {
        this.rateLimit = rateLimit;
        this.retrieval = retrieval;
        this.agent = agent;
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("version", version.get());
        values.put("rateLimit", Map.of("limit", rateLimit.getLimit(), "windowSeconds", rateLimit.getWindowSeconds()));
        values.put("retrieval", Map.of("parallelTimeoutMillis", retrieval.getParallelTimeoutMillis()));
        values.put("agent", Map.of("contextTokenBudget", agent.getContextTokenBudget(),
                "maxInputChars", agent.getMaxInputChars(), "maxOutputTokens", agent.getMaxOutputTokens()));
        return values;
    }

    public synchronized Map<String, Object> update(Map<String, Object> values) {
        if (values == null) {
            return snapshot();
        }
        updateInt(values, "rateLimit", "limit", 1, 10_000, rateLimit::setLimit);
        updateInt(values, "rateLimit", "windowSeconds", 1, 86_400, rateLimit::setWindowSeconds);
        updateLong(values, "retrieval", "parallelTimeoutMillis", 100, 120_000, retrieval::setParallelTimeoutMillis);
        updateInt(values, "agent", "contextTokenBudget", 256, 128_000, agent::setContextTokenBudget);
        updateInt(values, "agent", "maxInputChars", 1, 1_000_000, agent::setMaxInputChars);
        updateInt(values, "agent", "maxOutputTokens", 16, 32_768, agent::setMaxOutputTokens);
        long nextVersion = version.incrementAndGet();
        log.info("运行时配置已更新: version={}, keys={}", nextVersion, values.keySet());
        return snapshot();
    }

    private void updateInt(Map<String, Object> root, String group, String key, int min, int max,
            java.util.function.IntConsumer consumer) {
        Object value = nested(root, group, key);
        if (value == null) return;
        if (!(value instanceof Number number) || !isInteger(number.doubleValue())) {
            throw invalid(group, key);
        }
        int parsed = number.intValue();
        if (parsed < min || parsed > max) throw invalid(group, key);
        consumer.accept(parsed);
    }

    private void updateLong(Map<String, Object> root, String group, String key, long min, long max,
            java.util.function.LongConsumer consumer) {
        Object value = nested(root, group, key);
        if (value == null) return;
        if (!(value instanceof Number number) || !isInteger(number.doubleValue())) {
            throw invalid(group, key);
        }
        long parsed = number.longValue();
        if (parsed < min || parsed > max) throw invalid(group, key);
        consumer.accept(parsed);
    }

    private boolean isInteger(double value) {
        return Double.isFinite(value) && value == Math.rint(value);
    }

    private BusinessException invalid(String group, String key) {
        return new BusinessException(ErrorCode.BAD_REQUEST, "运行时配置值无效: " + group + "." + key);
    }

    private Object nested(Map<String, Object> root, String group, String key) {
        Object child = root.get(group);
        return child instanceof Map<?, ?> map ? map.get(key) : null;
    }
}
