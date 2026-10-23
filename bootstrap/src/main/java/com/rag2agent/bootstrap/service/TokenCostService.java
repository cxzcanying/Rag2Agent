package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.config.TokenCostProperties;
import com.rag2agent.bootstrap.tenancy.TenantContext;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import com.rag2agent.infra.ai.model.ChatMessage;
import com.rag2agent.infra.ai.model.ToolDef;
import com.rag2agent.infra.ai.model.VisionResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 每次模型请求独立记账，时间归属使用请求开始或厂商创建时间，而不是完成时间。 */
@Service
public class TokenCostService {

    private static final Logger log = LoggerFactory.getLogger(TokenCostService.class);
    private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Shanghai");

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

    public void record(
            Map<String, Object> usage,
            List<ChatMessage> messages,
            List<ToolDef> tools,
            String provider,
            String model) {
        record(CostContext.current("chat"), usage, messages, tools, provider, model);
    }

    public void record(
            CostContext context,
            Map<String, Object> usage,
            List<ChatMessage> messages,
            List<ToolDef> tools,
            String provider,
            String model) {
        if (usage == null) {
            recordFailure(context, provider, model, "UNKNOWN");
            return;
        }
        long prompt = number(usage.get("prompt_tokens"));
        long completion = number(usage.get("completion_tokens"));
        long total = number(usage.get("total_tokens"));
        long cached = firstPositive(
                nestedNumber(usage, "prompt_tokens_details", "cached_tokens"),
                number(usage.get("prompt_cache_hit_tokens")),
                number(usage.get("cache_read_input_tokens")));
        long reasoning = nestedNumber(usage, "completion_tokens_details", "reasoning_tokens");
        long estimated = estimateMessages(messages, tools);
        Instant providerCreatedAt = providerCreatedAt(usage);
        Instant billingTime = providerCreatedAt == null
                ? context.effectiveStartedAt() : providerCreatedAt;
        PriceResult result = calculate(provider, model, billingTime,
                prompt, completion, cached);
        persist(
                context, provider, model, "SUCCESS", prompt, completion, total,
                cached, Math.max(0, prompt - cached), reasoning, estimated,
                result, providerCreatedAt, context.effectiveStartedAt(), Instant.now());
        if (result.resolved() != null) {
            meterRegistry.counter("rag2agent.ai.cost",
                    "provider", provider, "model", model,
                    "function", context.function()).increment(result.totalCost());
        }
        recordCalibration(estimated, prompt, provider, model);
    }

    public void recordExternal(
            CostContext context,
            String provider,
            String model,
            String function,
            long inputTokens,
            long outputTokens) {
        CostContext effective = context == null
                ? new CostContext(null, null, null, null, TenantContext.getTenantId(), function, Instant.now())
                : new CostContext(
                        context.requestId(), context.taskId(), context.runId(), context.userId(),
                        context.tenantId(), function, context.startedAt());
        PriceResult result = calculate(provider, model, effective.effectiveStartedAt(),
                inputTokens, outputTokens, 0);
        persist(
                effective, provider, model, "SUCCESS", inputTokens, outputTokens,
                inputTokens + outputTokens, 0, inputTokens, 0, inputTokens,
                result, null, effective.effectiveStartedAt(), Instant.now());
    }

    public void recordFailure(CostContext context, String provider, String model, String status) {
        CostContext effective = context == null ? CostContext.current("unknown") : context;
        persist(
                effective, provider, model, status, 0, 0, 0, 0, 0, 0, 0,
                new PriceResult(null, 0, 0, 0), null,
                effective.effectiveStartedAt(), Instant.now());
    }

    public void recordVision(CostContext context, String prompt, VisionResult result) {
        List<ChatMessage> messages = List.of(new ChatMessage("user", prompt));
        Map<String, Object> usage = result.usage();
        if (usage == null || usage.isEmpty()) {
            long promptTokens = estimateMessages(messages, List.of());
            long completionTokens = estimateText(result.content());
            usage = Map.of(
                    "prompt_tokens", promptTokens,
                    "completion_tokens", completionTokens,
                    "total_tokens", promptTokens + completionTokens);
        }
        record(
                context,
                usage,
                messages,
                List.of(),
                result.provider(),
                result.model());
    }

    @Transactional
    public void reserve(
            CostContext context,
            List<ChatMessage> messages,
            List<ToolDef> tools,
            String provider,
            String model,
            int maxOutputTokens) {
        if (jdbc == null || properties.getDailyBudgetUsd() <= 0 || context == null) {
            return;
        }
        Long tenantId = context.tenantId();
        if (tenantId == null) {
            return;
        }
        PriceResult estimate = calculate(
                provider, model, context.effectiveStartedAt(),
                estimateMessages(messages, tools), Math.max(0, maxOutputTokens), 0);
        if (estimate.resolved() == null
                || !"USD".equalsIgnoreCase(estimate.resolved().currency())) {
            return;
        }
        LocalDate billingDate = context.effectiveStartedAt()
                .atZone(BILLING_ZONE).toLocalDate();
        String lockKey = "ai-budget:" + billingDate + ":" + tenantId;
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", lockKey);
        expireStaleReservations(billingDate, tenantId);
        BigDecimal spent = jdbc.queryForObject("""
                SELECT COALESCE((
                           SELECT sum(total_cost) FROM ai_usage_ledger
                           WHERE (timezone('Asia/Shanghai', COALESCE(
                                      provider_created_at, started_at, created_at)))::date = ?
                             AND tenant_id = ?
                       ), 0)
                     + COALESCE((
                           SELECT sum(reserved_cost) FROM ai_budget_reservation
                           WHERE billing_date = ? AND tenant_id = ? AND status = 'RESERVED'
                       ), 0)
                """, BigDecimal.class, billingDate, tenantId, billingDate, tenantId);
        BigDecimal remaining = BigDecimal.valueOf(properties.getDailyBudgetUsd())
                .subtract(spent == null ? BigDecimal.ZERO : spent);
        BigDecimal reserveCost = BigDecimal.valueOf(estimate.totalCost());
        if (reserveCost.compareTo(remaining) > 0) {
            throw new BusinessException(
                    ErrorCode.RATE_LIMITED, "每日 AI 预算已耗尽，请稍后重试");
        }
        jdbc.update("""
                INSERT INTO ai_budget_reservation
                    (request_id, billing_date, tenant_id, user_id, function_name,
                     reserved_cost, currency)
                VALUES (?, ?, ?, ?, ?, ?, 'USD')
                ON CONFLICT (request_id) DO NOTHING
                """, context.effectiveRequestId(), billingDate, tenantId,
                context.userId(), context.function(), reserveCost);
    }

    public BudgetStatus budgetStatus() {
        LocalDate date = LocalDate.now(BILLING_ZONE);
        long tenantId = TenantContext.requireTenantId();
        if (jdbc == null) {
            return new BudgetStatus(date, properties.getDailyBudgetUsd(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "USD");
        }
        expireStaleReservations(date, tenantId);
        BigDecimal actual = jdbc.queryForObject("""
                SELECT COALESCE(sum(total_cost), 0) FROM ai_usage_ledger
                WHERE (timezone('Asia/Shanghai', COALESCE(
                           provider_created_at, started_at, created_at)))::date = ?
                  AND tenant_id = ?
                """, BigDecimal.class, date, tenantId);
        BigDecimal reserved = jdbc.queryForObject("""
                SELECT COALESCE(sum(reserved_cost), 0) FROM ai_budget_reservation
                WHERE billing_date = ? AND tenant_id = ? AND status = 'RESERVED'
                """, BigDecimal.class, date, tenantId);
        actual = actual == null ? BigDecimal.ZERO : actual;
        reserved = reserved == null ? BigDecimal.ZERO : reserved;
        BigDecimal limit = BigDecimal.valueOf(properties.getDailyBudgetUsd());
        return new BudgetStatus(
                date, properties.getDailyBudgetUsd(), actual, reserved,
                limit.subtract(actual).subtract(reserved).max(BigDecimal.ZERO), "USD");
    }

    public void recordEstimated(List<ChatMessage> messages, String provider, String model) {
        recordEstimated(messages, null, provider, model);
    }

    public void recordEstimated(
            List<ChatMessage> messages, List<ToolDef> tools, String provider, String model) {
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
        if (messages == null) {
            return 0;
        }
        long messageTokens = messages.stream()
                .map(ChatMessage::content)
                .filter(Objects::nonNull)
                .mapToLong(this::estimateText)
                .sum();
        long toolTokens = tools == null ? 0 : estimateText(tools.toString());
        return messageTokens + toolTokens;
    }

    private PriceResult calculate(
            String provider,
            String model,
            Instant billingTime,
            long prompt,
            long completion,
            long cached) {
        TokenCostProperties.Price price = properties.getPrices().get(provider + "/" + model);
        TokenCostProperties.Price.ResolvedPrice resolved =
                price == null ? null : price.resolve(billingTime);
        if (resolved == null) {
            return new PriceResult(null, 0, 0, 0);
        }
        long billablePrompt = Math.max(0, prompt - cached);
        double promptCost = billablePrompt * resolved.promptPerMillion() / 1_000_000d;
        double cacheCost = cached * resolved.cacheReadPerMillion() / 1_000_000d;
        double completionCost = completion * resolved.completionPerMillion() / 1_000_000d;
        return new PriceResult(resolved, promptCost, cacheCost, completionCost);
    }

    private void persist(
            CostContext context,
            String provider,
            String model,
            String status,
            long prompt,
            long completion,
            long total,
            long cached,
            long cacheMiss,
            long reasoning,
            long estimated,
            PriceResult price,
            Instant providerCreatedAt,
            Instant startedAt,
            Instant completedAt) {
        if (jdbc == null) {
            return;
        }
        String priceVersion = price.resolved() == null ? null : price.resolved().version();
        String tier = priceVersion == null
                ? "unknown"
                : priceVersion.endsWith("-off-peak")
                        ? "off-peak"
                        : priceVersion.endsWith("-peak") ? "peak" : "unknown";
        try {
            jdbc.update("""
                    INSERT INTO ai_usage_ledger
                        (provider, model, prompt_tokens, completion_tokens, total_tokens,
                         estimated_prompt_tokens, cached_prompt_tokens, prompt_cost,
                         completion_cost, total_cost, currency, price_version,
                         request_id, task_id, run_id, user_id, tenant_id, function_name,
                         started_at, provider_created_at, completed_at,
                         cache_hit_tokens, cache_miss_tokens, input_tokens, output_tokens,
                         reasoning_tokens, billing_tier, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    provider, model, prompt, completion, total, estimated, cached,
                    price.promptCost(), price.completionCost() + price.cacheCost(),
                    price.totalCost(),
                    price.resolved() == null ? null : price.resolved().currency(),
                    price.resolved() == null ? null : price.resolved().version(),
                    context.effectiveRequestId(), context.taskId(), context.runId(),
                    context.userId(), context.tenantId(), context.function(),
                    java.sql.Timestamp.from(startedAt),
                    providerCreatedAt == null ? null : java.sql.Timestamp.from(providerCreatedAt),
                    java.sql.Timestamp.from(completedAt),
                    cached, cacheMiss, prompt, completion, reasoning, tier, status);
            jdbc.update("""
                    UPDATE ai_budget_reservation
                    SET status = ?, actual_cost = ?, settled_at = now()
                    WHERE request_id = ?
                    """, "SUCCESS".equals(status) ? "SETTLED" : "FAILED",
                    price.totalCost(), context.effectiveRequestId());
        } catch (DataAccessException exception) {
            log.warn("AI 成本账本写入失败，不影响模型响应: provider={}, model={}",
                    provider, model, exception);
        }
    }

    private void recordCalibration(long estimated, long actual, String provider, String model) {
        if (actual <= 0 || estimated <= 0) {
            return;
        }
        meterRegistry.summary("rag2agent.ai.tokens.calibration.ratio",
                "provider", provider, "model", model).record((double) actual / estimated);
        meterRegistry.summary("rag2agent.ai.tokens.calibration.delta",
                "provider", provider, "model", model).record(actual - estimated);
    }

    private void expireStaleReservations(LocalDate date, long tenantId) {
        jdbc.update("""
                UPDATE ai_budget_reservation
                SET status = 'EXPIRED', actual_cost = 0, settled_at = now()
                WHERE billing_date = ? AND tenant_id = ?
                  AND status = 'RESERVED' AND created_at < now() - interval '1 hour'
                """, date, tenantId);
    }

    private long estimateText(String text) {
        long tokens = 0;
        int asciiRun = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                tokens++;
                asciiRun = 0;
            } else if (Character.isWhitespace(cp) || "{}[]():,\"'.;<>/\\".indexOf(cp) >= 0) {
                tokens += asciiRun == 0 ? 1 : 0;
                asciiRun = 0;
            } else {
                asciiRun++;
                if (asciiRun == 4) {
                    tokens++;
                    asciiRun = 0;
                }
            }
        }
        return tokens + (asciiRun > 0 ? 1 : 0);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static long firstPositive(long... values) {
        for (long value : values) {
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private static long nestedNumber(Map<String, Object> map, String parent, String child) {
        Object value = map.get(parent);
        return value instanceof Map<?, ?> nested ? number(nested.get(child)) : 0;
    }

    private static Instant providerCreatedAt(Map<String, Object> usage) {
        long created = number(usage.get("created"));
        return created > 0 ? Instant.ofEpochSecond(created) : null;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4dbf) || (cp >= 0x4e00 && cp <= 0x9fff)
                || (cp >= 0xf900 && cp <= 0xfaff);
    }

    public record CostContext(
            String requestId,
            Long taskId,
            Long runId,
            Long userId,
            Long tenantId,
            String function,
            Instant startedAt) {

        public static CostContext current(String function) {
            return current(function, Instant.now());
        }

        public static CostContext current(String function, Instant startedAt) {
            return new CostContext(
                    UUID.randomUUID().toString(), null, null, currentUserId(),
                    TenantContext.getTenantId(), function, startedAt);
        }

        Instant effectiveStartedAt() {
            return startedAt == null ? Instant.now() : startedAt;
        }

        String effectiveRequestId() {
            return requestId == null || requestId.isBlank()
                    ? UUID.randomUUID().toString()
                    : requestId;
        }

        private static Long currentUserId() {
            try {
                return cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    public record BudgetStatus(
            LocalDate date,
            double dailyLimitUsd,
            BigDecimal actualCost,
            BigDecimal reservedCost,
            BigDecimal remainingUsd,
            String currency) {}

    private record PriceResult(
            TokenCostProperties.Price.ResolvedPrice resolved,
            double promptCost,
            double cacheCost,
            double completionCost) {

        double totalCost() {
            return promptCost + cacheCost + completionCost;
        }
    }
}
