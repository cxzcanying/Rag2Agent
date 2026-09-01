package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.config.TokenCostProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import com.rag2agent.infra.ai.model.ChatMessage;
import com.rag2agent.infra.ai.model.ToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Token 统计与成本估算；没有 provider 价格时只记录 token，不伪造金额。 */
@Service
public class TokenCostService {
    private static final Logger log = LoggerFactory.getLogger(TokenCostService.class);
    private final TokenCostProperties properties;
    private final MeterRegistry meterRegistry;
    private final JdbcTemplate jdbc;

    @Autowired
    public TokenCostService(TokenCostProperties properties, MeterRegistry meterRegistry, JdbcTemplate jdbc) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.jdbc = jdbc;
    }

    public TokenCostService(TokenCostProperties properties, MeterRegistry meterRegistry) {
        this(properties, meterRegistry, null);
    }

    public void record(Map<String, Object> usage, String provider, String model) {
        record(usage, null, null, provider, model);
    }

    public void record(Map<String, Object> usage, List<ChatMessage> messages, String provider, String model) {
        record(usage, messages, null, provider, model);
    }

    public void record(Map<String, Object> usage, List<ChatMessage> messages,
            List<ToolDef> tools, String provider, String model) {
        if (usage == null) return;
        long prompt = number(usage.get("prompt_tokens"));
        long completion = number(usage.get("completion_tokens"));
        long total = number(usage.get("total_tokens"));
        long cached = firstPositive(
                nestedNumber(usage, "prompt_tokens_details", "cached_tokens"),
                number(usage.get("prompt_cache_hit_tokens")),
                number(usage.get("cache_read_input_tokens")));
        long estimated = estimateMessages(messages, tools);
        TokenCostProperties.Price price = properties.getPrices().get(provider + "/" + model);
        TokenCostProperties.Price.ResolvedPrice resolved = price == null ? null : price.resolve(Instant.now());
        double promptCost = 0;
        double completionCost = 0;
        if (resolved != null) {
            long billablePrompt = Math.max(0, prompt - cached);
            promptCost = billablePrompt * resolved.promptPerMillion() / 1_000_000d
                    + cached * resolved.cacheReadPerMillion() / 1_000_000d;
            completionCost = completion * resolved.completionPerMillion() / 1_000_000d;
            double cost = promptCost + completionCost;
            meterRegistry.counter("rag2agent.ai.cost", "provider", provider, "model", model).increment(cost);
        }
        if (jdbc != null) {
            try {
                jdbc.update("""
                        INSERT INTO ai_usage_ledger
                        (provider, model, prompt_tokens, completion_tokens, total_tokens,
                         estimated_prompt_tokens, cached_prompt_tokens, prompt_cost, completion_cost,
                         total_cost, currency, price_version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, provider, model, prompt, completion, total, estimated, cached,
                        promptCost, completionCost, promptCost + completionCost,
                        resolved == null ? null : resolved.currency(), resolved == null ? null : resolved.version());
            } catch (DataAccessException exception) {
                log.warn("AI 成本账本写入失败，不影响模型请求: provider={}, model={}", provider, model, exception);
            }
        }
        recordCalibration(estimated, prompt, provider, model);
    }

    public void recordEstimated(List<ChatMessage> messages, String provider, String model) {
        recordEstimated(messages, null, provider, model);
    }

    public void recordEstimated(List<ChatMessage> messages, List<ToolDef> tools, String provider, String model) {
        long estimated = estimateMessages(messages, tools);
        meterRegistry.counter("rag2agent.ai.tokens", "type", "estimated",
                "provider", provider == null || provider.isBlank() ? "unknown" : provider,
                "model", model == null || model.isBlank() ? "configured" : model)
                .increment(estimated);
    }

    public long estimateMessages(List<ChatMessage> messages) {
        return estimateMessages(messages, null);
    }

    public long estimateMessages(List<ChatMessage> messages, List<ToolDef> tools) {
        if (messages == null) return 0;
        long messageTokens = messages.stream().map(ChatMessage::content).filter(java.util.Objects::nonNull)
                .mapToLong(this::estimateText).sum();
        long toolTokens = tools == null ? 0 : estimateText(tools.toString());
        return messageTokens + toolTokens;
    }

    private long estimateText(String text) {
        long tokens = 0;
        int asciiRun = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                tokens += 1;
                asciiRun = 0;
            } else if (Character.isWhitespace(cp) || "{}[]():,\"'.;<>/\\".indexOf(cp) >= 0) {
                tokens += asciiRun == 0 ? 1 : 0;
                asciiRun = 0;
            } else {
                asciiRun++;
                if (asciiRun == 4) { tokens++; asciiRun = 0; }
            }
        }
        return tokens + (asciiRun > 0 ? 1 : 0);
    }

    private void recordCalibration(long estimated, long actual, String provider, String model) {
        if (actual <= 0 || estimated <= 0) return;
        meterRegistry.summary("rag2agent.ai.tokens.calibration.ratio", "provider", provider, "model", model)
                .record((double) actual / estimated);
        meterRegistry.summary("rag2agent.ai.tokens.calibration.delta", "provider", provider, "model", model)
                .record(actual - estimated);
    }

    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    private static long firstPositive(long... values) {
        for (long value : values) if (value > 0) return value;
        return 0;
    }
    @SuppressWarnings("unchecked")
    private static long nestedNumber(Map<String, Object> map, String parent, String child) {
        Object value = map.get(parent);
        return value instanceof Map<?, ?> nested ? number(nested.get(child)) : 0;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4dbf) || (cp >= 0x4e00 && cp <= 0x9fff)
                || (cp >= 0xf900 && cp <= 0xfaff);
    }
}
