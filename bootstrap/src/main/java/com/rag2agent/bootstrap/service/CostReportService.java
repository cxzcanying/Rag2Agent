package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.config.SecurityProperties;
import com.rag2agent.bootstrap.entity.AppUser;
import com.rag2agent.bootstrap.mapper.AppUserMapper;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import com.rag2agent.bootstrap.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CostReportService {

    private final JdbcTemplate jdbc;
    private final AppUserMapper userMapper;
    private final SecurityProperties securityProperties;

    public CostReportService(
            JdbcTemplate jdbc,
            AppUserMapper userMapper,
            SecurityProperties securityProperties) {
        this.jdbc = jdbc;
        this.userMapper = userMapper;
        this.securityProperties = securityProperties;
    }

    public CostReport summary(
            Long actorUserId, Long requestedUserId, LocalDate from, LocalDate to,
            String model, String function) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        if (start.isAfter(end) || start.isBefore(end.minusDays(90))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "成本查询日期范围必须在 90 天内");
        }
        Long userId = requestedUserId;
        boolean admin = isAdmin(actorUserId);
        if (userId != null && !admin) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员可以查看其他用户成本");
        }
        if (userId == null) {
            userId = actorUserId;
        }
        Long tenantId = TenantContext.requireTenantId();
        String filter = """
                  AND l.user_id = ?
                  AND l.tenant_id = ?
                  AND (? = '' OR l.model = ?)
                  AND (? = '' OR l.function_name = ?)
                """;
        List<DailyCost> daily = jdbc.query("""
                SELECT (timezone('Asia/Shanghai', COALESCE(
                           l.provider_created_at, l.started_at, l.created_at)))::date AS billing_date,
                       count(*) AS requests,
                       COALESCE(sum(l.input_tokens), 0) AS input_tokens,
                       COALESCE(sum(l.output_tokens), 0) AS output_tokens,
                       COALESCE(sum(l.total_cost), 0) AS total_cost,
                       COALESCE(sum(l.cache_hit_tokens), 0) AS cache_hit_tokens,
                       COALESCE(sum(l.cache_hit_tokens + l.cache_miss_tokens), 0) AS prompt_tokens,
                       COALESCE(sum(l.total_cost) FILTER (WHERE l.billing_tier = 'peak'), 0) AS peak_cost,
                       COALESCE(sum(l.total_cost) FILTER (WHERE l.billing_tier = 'off-peak'), 0) AS off_peak_cost
                FROM ai_usage_ledger l
                WHERE (timezone('Asia/Shanghai', COALESCE(
                           l.provider_created_at, l.started_at, l.created_at)))::date BETWEEN ? AND ?
                """ + filter + """
                GROUP BY billing_date
                ORDER BY billing_date
                """,
                (rs, rowNum) -> new DailyCost(
                        rs.getDate("billing_date").toLocalDate(),
                        rs.getLong("requests"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getBigDecimal("total_cost"),
                        ratio(rs.getLong("cache_hit_tokens"), rs.getLong("prompt_tokens")),
                        rs.getBigDecimal("peak_cost"),
                        rs.getBigDecimal("off_peak_cost")),
                start, end, userId, tenantId, model == null ? "" : model,
                model == null ? "" : model, function == null ? "" : function,
                function == null ? "" : function);
        List<RunCost> runs = jdbc.query("""
                SELECT l.run_id,
                       count(*) AS requests,
                       count(DISTINCT (timezone('Asia/Shanghai', COALESCE(
                           l.provider_created_at, l.started_at, l.created_at)))::date) AS days,
                       COALESCE(sum(l.total_tokens), 0) AS total_tokens,
                       COALESCE(sum(l.total_cost), 0) AS total_cost
                FROM ai_usage_ledger l
                WHERE l.run_id IS NOT NULL
                  AND l.user_id = ? AND l.tenant_id = ?
                  AND (timezone('Asia/Shanghai', COALESCE(
                      l.provider_created_at, l.started_at, l.created_at)))::date BETWEEN ? AND ?
                GROUP BY l.run_id
                ORDER BY total_cost DESC
                LIMIT 100
                """, (rs, rowNum) -> new RunCost(
                rs.getLong("run_id"), rs.getLong("requests"), rs.getLong("days"),
                rs.getLong("total_tokens"), rs.getBigDecimal("total_cost")),
                userId, tenantId, start, end);
        List<TaskCost> tasks = jdbc.query("""
                SELECT l.task_id, l.run_id, l.function_name,
                       count(*) AS requests,
                       COALESCE(sum(l.total_tokens), 0) AS total_tokens,
                       COALESCE(sum(l.total_cost), 0) AS total_cost
                FROM ai_usage_ledger l
                WHERE l.task_id IS NOT NULL
                  AND l.user_id = ? AND l.tenant_id = ?
                  AND (timezone('Asia/Shanghai', COALESCE(
                      l.provider_created_at, l.started_at, l.created_at)))::date BETWEEN ? AND ?
                GROUP BY l.task_id, l.run_id, l.function_name
                ORDER BY total_cost DESC
                LIMIT 100
                """, (rs, rowNum) -> new TaskCost(
                rs.getLong("task_id"),
                rs.getObject("run_id", Long.class),
                rs.getString("function_name"),
                rs.getLong("requests"),
                rs.getLong("total_tokens"),
                rs.getBigDecimal("total_cost")),
                userId, tenantId, start, end);
        List<ProviderBalance> balances = admin ? jdbc.query("""
                SELECT DISTINCT ON (provider) provider, currency, balance, source, checked_at
                FROM ai_provider_balance
                ORDER BY provider, checked_at DESC
                """, (rs, rowNum) -> new ProviderBalance(
                rs.getString("provider"), rs.getString("currency"),
                rs.getBigDecimal("balance"), rs.getString("source"),
                rs.getTimestamp("checked_at").toInstant())) : List.of();
        BigDecimal total = daily.stream()
                .map(DailyCost::totalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CostReport(start, end, total, daily, runs, tasks, balances);
    }

    public void recordBalance(
            String provider, String currency, BigDecimal balance, String source) {
        jdbc.update("""
                INSERT INTO ai_provider_balance(provider, currency, balance, source)
                VALUES (?, ?, ?, ?)
                """, provider, currency, balance, source == null ? "manual" : source);
    }

    private boolean isAdmin(Long userId) {
        AppUser user = userMapper.selectById(userId);
        return user != null && securityProperties.getAdminUsernames().contains(user.getUsername());
    }

    private static double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0 : (double) numerator / denominator;
    }

    public record CostReport(
            LocalDate from,
            LocalDate to,
            BigDecimal totalCost,
            List<DailyCost> daily,
            List<RunCost> runs,
            List<TaskCost> tasks,
            List<ProviderBalance> balances) {}

    public record DailyCost(
            LocalDate date,
            long requests,
            long inputTokens,
            long outputTokens,
            BigDecimal totalCost,
            double cacheHitRate,
            BigDecimal peakCost,
            BigDecimal offPeakCost) {}

    public record RunCost(
            Long runId,
            long requests,
            long days,
            long totalTokens,
            BigDecimal totalCost) {}

    public record TaskCost(
            Long taskId,
            Long runId,
            String function,
            long requests,
            long totalTokens,
            BigDecimal totalCost) {}

    public record ProviderBalance(
            String provider,
            String currency,
            BigDecimal balance,
            String source,
            java.time.Instant checkedAt) {}
}
