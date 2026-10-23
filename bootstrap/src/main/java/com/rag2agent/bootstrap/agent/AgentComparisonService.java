package com.rag2agent.bootstrap.agent;

import com.rag2agent.bootstrap.dto.AgentDtos.AgentComparisonRequest;
import com.rag2agent.bootstrap.dto.AgentDtos.AgentComparisonResponse;
import com.rag2agent.bootstrap.dto.AgentDtos.ComparisonRun;
import com.rag2agent.bootstrap.dto.AgentDtos.TeamResearchRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 手动触发单 Agent 与多 Agent 的同题对比，返回状态、耗时、token、费用和简单答案匹配度。 */
@Service
public class AgentComparisonService {

    private final AgentRunService agentRunService;
    private final MultiAgentResearchService multiAgentResearchService;
    private final JdbcTemplate jdbc;

    public AgentComparisonService(
            AgentRunService agentRunService,
            MultiAgentResearchService multiAgentResearchService,
            JdbcTemplate jdbc) {
        this.agentRunService = agentRunService;
        this.multiAgentResearchService = multiAgentResearchService;
        this.jdbc = jdbc;
    }

    public AgentComparisonResponse compare(Long userId, AgentComparisonRequest request) {
        long singleStarted = System.nanoTime();
        AgentExecutionResult single = agentRunService.start(
                userId,
                "compare-single-" + UUID.randomUUID(),
                request.query(),
                request.kbId(),
                event -> {});
        long singleMs = elapsedMillis(singleStarted);

        long teamStarted = System.nanoTime();
        var team = multiAgentResearchService.research(
                userId, new TeamResearchRequest(request.kbId(), request.query()));
        long teamMs = elapsedMillis(teamStarted);

        return new AgentComparisonResponse(
                request.query(),
                runReport(single.runId(), single.status(), single.answer(),
                        singleMs, request),
                runReport(team.runId(), team.status(), team.answer(),
                        teamMs, request));
    }

    private ComparisonRun runReport(
            Long runId,
            String status,
            String answer,
            long latencyMs,
            AgentComparisonRequest request) {
        Cost aggregate = jdbc.queryForObject("""
                SELECT count(*) AS requests,
                       COALESCE(sum(total_tokens), 0) AS total_tokens,
                       COALESCE(sum(total_cost), 0) AS total_cost
                FROM ai_usage_ledger
                WHERE run_id = ?
                """, (rs, rowNum) -> new Cost(
                rs.getLong("requests"),
                rs.getLong("total_tokens"),
                rs.getBigDecimal("total_cost")), runId);
        BigDecimal cost = aggregate == null ? BigDecimal.ZERO : aggregate.cost();
        return new ComparisonRun(
                runId, status, answer, latencyMs,
                aggregate == null ? 0 : aggregate.requests(),
                aggregate == null ? 0 : aggregate.tokens(),
                cost,
                answerMatch(answer, request.expectedAnswer()));
    }

    private Double answerMatch(String answer, String expectedAnswer) {
        if (expectedAnswer == null || expectedAnswer.isBlank()) {
            return null;
        }
        return answer != null && answer.replaceAll("\\s+", "")
                .contains(expectedAnswer.replaceAll("\\s+", "")) ? 1.0 : 0.0;
    }

    private long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedNanos);
    }

    private record Cost(long requests, long tokens, BigDecimal cost) {}
}
