package com.rag2agent.bootstrap.agent;

import com.rag2agent.bootstrap.config.AgentProperties;
import com.rag2agent.bootstrap.config.RecoveryProperties;
import com.rag2agent.bootstrap.entity.AgentRun;
import com.rag2agent.bootstrap.mapper.AgentRunMapper;
import com.rag2agent.bootstrap.mapper.ToolCallRecordMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审批超时自动终态化：把挂起超过 {@code approvalTimeoutSeconds} 的 Agent 执行置为 FAILED，
 * 并把对应挂起的 tool_call 置为 TIMED_OUT，避免 WAITING_APPROVAL 无限积压。
 * <p>
 * 之前审批超时只靠 Redis 上下文 30 分钟 TTL 兜底：到期后点"通过"会因上下文过期而报错，
 * 但 run 和 tool_call 不会自动进入终态。这里补上定时器，让超时审批有一个明确终态。
 * @author 21311
 */
@Component
public class AgentApprovalTimeoutScanner {

    private static final Logger log = LoggerFactory.getLogger(AgentApprovalTimeoutScanner.class);

    private final AgentRunMapper runMapper;
    private final ToolCallRecordMapper toolCallMapper;
    private final AgentProperties agentProperties;
    private final RecoveryProperties recoveryProperties;
    private final MeterRegistry meterRegistry;

    public AgentApprovalTimeoutScanner(
            AgentRunMapper runMapper,
            ToolCallRecordMapper toolCallMapper,
            AgentProperties agentProperties,
            RecoveryProperties recoveryProperties,
            MeterRegistry meterRegistry) {
        this.runMapper = runMapper;
        this.toolCallMapper = toolCallMapper;
        this.agentProperties = agentProperties;
        this.recoveryProperties = recoveryProperties;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(initialDelay = 5000, fixedDelayString = "${rag2agent.recovery.scan-interval-ms:60000}")
    public void scan() {
        if (!recoveryProperties.isEnabled()) {
            return;
        }
        long timeoutSeconds = agentProperties.getApprovalTimeoutSeconds();
        List<AgentRun> stale = runMapper.listStaleWaitingApproval(timeoutSeconds);
        for (AgentRun run : stale) {
            // CAS：只有仍处于 WAITING_APPROVAL 才终态化，避免覆盖并发审批结果。
            int updated = runMapper.markApprovalTimeout(run.getId(), "审批超时，操作未执行，请重新发起");
            if (updated == 1) {
                toolCallMapper.timeoutPendingApproval(run.getId(), "审批已超时");
                meterRegistry.counter("rag2agent.agent.approval.timeout", "outcome", "finalized").increment();
                log.info("审批超时已终态化: runId={}", run.getId());
            }
        }
    }
}
