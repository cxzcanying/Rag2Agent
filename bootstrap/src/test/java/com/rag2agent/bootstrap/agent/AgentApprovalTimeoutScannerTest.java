package com.rag2agent.bootstrap.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rag2agent.bootstrap.config.AgentProperties;
import com.rag2agent.bootstrap.config.RecoveryProperties;
import com.rag2agent.bootstrap.entity.AgentRun;
import com.rag2agent.bootstrap.mapper.AgentRunMapper;
import com.rag2agent.bootstrap.mapper.ToolCallRecordMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentApprovalTimeoutScannerTest {

    @Test
    void finalizesStaleApprovalAndTimesOutPendingToolCall() {
        AgentRun stale = new AgentRun();
        stale.setId(7L);
        stale.setStatus("WAITING_APPROVAL");
        AgentRunMapper runs = mock(AgentRunMapper.class);
        when(runs.listStaleWaitingApproval(1800)).thenReturn(List.of(stale));
        when(runs.markApprovalTimeout(eq(7L), anyString())).thenReturn(1);
        ToolCallRecordMapper calls = mock(ToolCallRecordMapper.class);

        AgentApprovalTimeoutScanner scanner = new AgentApprovalTimeoutScanner(
                runs, calls, new AgentProperties(), new RecoveryProperties(), new SimpleMeterRegistry());

        scanner.scan();

        verify(runs).listStaleWaitingApproval(1800);
        verify(runs).markApprovalTimeout(eq(7L), anyString());
        verify(calls).timeoutPendingApproval(7L, "审批已超时");
    }

    @Test
    void doesNotFinalizeWhenApprovalStillClaimedByAnother() {
        AgentRun stale = new AgentRun();
        stale.setId(8L);
        stale.setStatus("WAITING_APPROVAL");
        AgentRunMapper runs = mock(AgentRunMapper.class);
        when(runs.listStaleWaitingApproval(1800)).thenReturn(List.of(stale));
        // CAS 抢占失败（已被并发审批处理），不应再把 tool_call 置为 TIMED_OUT。
        when(runs.markApprovalTimeout(eq(8L), anyString())).thenReturn(0);
        ToolCallRecordMapper calls = mock(ToolCallRecordMapper.class);

        AgentApprovalTimeoutScanner scanner = new AgentApprovalTimeoutScanner(
                runs, calls, new AgentProperties(), new RecoveryProperties(), new SimpleMeterRegistry());

        scanner.scan();

        verify(calls, never()).timeoutPendingApproval(any(), anyString());
    }

    @Test
    void doesNothingWhenDisabled() {
        RecoveryProperties recovery = new RecoveryProperties();
        recovery.setEnabled(false);
        AgentRunMapper runs = mock(AgentRunMapper.class);
        AgentApprovalTimeoutScanner scanner = new AgentApprovalTimeoutScanner(
                runs, mock(ToolCallRecordMapper.class), new AgentProperties(), recovery,
                new SimpleMeterRegistry());

        scanner.scan();

        verifyNoInteractions(runs);
    }
}
