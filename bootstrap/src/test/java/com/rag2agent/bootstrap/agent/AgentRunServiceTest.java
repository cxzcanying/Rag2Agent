package com.rag2agent.bootstrap.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.rag2agent.bootstrap.config.AgentProperties;
import com.rag2agent.bootstrap.entity.AgentStep;
import com.rag2agent.bootstrap.mapper.AgentRunMapper;
import com.rag2agent.bootstrap.mapper.AgentStepMapper;
import com.rag2agent.bootstrap.mapper.ToolCallRecordMapper;
import com.rag2agent.bootstrap.tool.ToolExecutor;
import com.rag2agent.bootstrap.tool.ToolRegistry;
import com.rag2agent.bootstrap.entity.AgentRun;
import com.rag2agent.infra.ai.client.ChatModelClient;
import com.rag2agent.infra.ai.exception.AiClientException;
import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import com.rag2agent.infra.ai.model.ChatMessage;
import com.rag2agent.infra.ai.model.ToolCall;
import com.rag2agent.infra.ai.model.ToolDef;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;

class AgentRunServiceTest {

    @Test
    void clientRequestIdReplaysExistingRunWithoutCallingModel() {
        AgentRun existing = new AgentRun();
        existing.setId(41L);
        existing.setUserId(7L);
        existing.setStatus("COMPLETED");
        existing.setAnswer("已完成");
        AgentRunMapper runs = mock(AgentRunMapper.class);
        when(runs.selectByClientRequest(7L, "request-1")).thenReturn(existing);
        StringRedisTemplate redis = redisWithReferences();
        AgentRunService service = serviceWith(runs, redis);

        AgentExecutionResult result = service.start(7L, "session", "request-1", "问题", 3L, event -> {});

        assertEquals(41L, result.runId());
        assertEquals("COMPLETED", result.status());
        org.mockito.Mockito.verify(runs, org.mockito.Mockito.never()).insert(any(AgentRun.class));
    }

    @Test
    void duplicateInsertRereadsRunForConcurrentClientRequest() {
        AgentRun existing = new AgentRun();
        existing.setId(42L);
        existing.setUserId(7L);
        existing.setStatus("RUNNING");
        AgentRunMapper runs = mock(AgentRunMapper.class);
        when(runs.selectByClientRequest(7L, "request-2"))
                .thenReturn(null)
                .thenReturn(existing);
        when(runs.insert(any(AgentRun.class))).thenThrow(new DuplicateKeyException("duplicate"));
        AgentRunService service = serviceWith(runs, redisWithReferences());

        AgentExecutionResult result = service.start(7L, "session", "request-2", "问题", 3L, event -> {});

        assertEquals(42L, result.runId());
        assertEquals("RUNNING", result.status());
    }

    @Test
    void lockLeaseLossAndReleaseErrorsAreCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentRunService service = serviceWith(mock(AgentRunMapper.class), redisWithReferences(), registry);
        service.recordLeaseRenewal(0L);
        service.recordLeaseRenewalError();
        service.recordLeaseReleaseError();
        assertEquals(1.0, registry.get("rag2agent.lock.operations")
                .tag("lock", "agent-session").tag("operation", "renew")
                .tag("outcome", "lost").counter().count());
        assertEquals(1.0, registry.get("rag2agent.lock.operations")
                .tag("lock", "agent-session").tag("operation", "renew")
                .tag("outcome", "error").counter().count());
        assertEquals(1.0, registry.get("rag2agent.lock.operations")
                .tag("lock", "agent-session").tag("operation", "release")
                .tag("outcome", "error").counter().count());
    }

    @Test
    void maxStepsForcesFinalAnswerWithoutTools() {
        AtomicReference<ChatCompletionRequest> captured = new AtomicReference<>();
        ChatModelClient chatClient = chatClient(request -> {
            captured.set(request);
            return new ChatCompletionResponse("基于已有结果的结论", "stop", Map.of());
        });
        List<AgentEvent> events = new ArrayList<>();

        AgentExecutionResult result = service(chatClient).finalizeAtMaxSteps(
                9L,
                List.of(new ChatMessage("user", "问题")),
                List.of(new Reference(1L, 0, "资料")),
                3,
                events::add);

        assertEquals("MAX_STEPS_REACHED", result.status());
        assertEquals("基于已有结果的结论", result.answer());
        assertTrue(captured.get().tools().isEmpty());
        assertEquals("user", captured.get().messages().getLast().role());
        assertTrue(captured.get().messages().getLast().content().contains("禁止再调用任何工具"));
        assertEquals("done", events.getLast().type());
    }

    @Test
    void maxStepsFinalizationFailureReturnsSafeError() {
        ChatModelClient chatClient = chatClient(request -> {
            throw new AiClientException("upstream secret", AiClientException.Kind.TIMEOUT, 504, 0);
        });
        List<AgentEvent> events = new ArrayList<>();

        AgentExecutionResult result = service(chatClient).finalizeAtMaxSteps(
                10L, List.of(new ChatMessage("user", "问题")), List.of(), 2, events::add);

        assertEquals("FAILED", result.status());
        assertEquals("error", events.getLast().type());
        assertEquals("模型服务响应超时，请稍后重试", events.getLast().data());
    }

    @Test
    void toolCallGlobalCapForcesFinalSummaryWithoutExceedingBudget() {
        // 模型在工具调用阶段持续返回工具；总结阶段（无工具）返回内容。
        ChatModelClient chatClient = chatClient(request -> {
            if (request.tools() == null || request.tools().isEmpty()) {
                return new ChatCompletionResponse("已达上限的总结", "stop", Map.of());
            }
            return new ChatCompletionResponse(
                    null, "tool_calls", Map.of(),
                    List.of(new ToolCall("tc-1", "function", "search_kb", "{}")));
        });
        AgentProperties props = new AgentProperties();
        props.setMaxToolCalls(2);

        com.rag2agent.bootstrap.service.HybridSearchService search =
                mock(com.rag2agent.bootstrap.service.HybridSearchService.class);
        when(search.search(eq(3L), anyString(), eq(5))).thenReturn(List.of(
                new RetrievalResult("c1", "资料", 0.9, Map.of("documentId", 1, "chunkIndex", 0))));
        ToolRegistry tools = mock(ToolRegistry.class);
        when(tools.requiresApproval(anyString())).thenReturn(false);
        when(tools.toolDefs()).thenReturn(List.of(new ToolDef("search_kb", "d", Map.of())));
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any(), anyString(), any())).thenReturn("ok");
        AgentRunMapper runs = mock(AgentRunMapper.class);
        when(runs.selectByClientRequest(1L, "cap-1")).thenReturn(null);
        when(runs.insert(any(AgentRun.class))).thenAnswer(invocation -> {
            ((AgentRun) invocation.getArgument(0)).setId(99L);
            return 1;
        });
        AgentStepMapper steps = mock(AgentStepMapper.class);
        ToolCallRecordMapper calls = mock(ToolCallRecordMapper.class);
        StringRedisTemplate redis = redisWithReferences();
        com.rag2agent.bootstrap.service.KnowledgeBaseService kb =
                mock(com.rag2agent.bootstrap.service.KnowledgeBaseService.class);

        AgentRunService service = new AgentRunService(
                chatClient, search, tools, executor, runs, steps, calls,
                new ObjectMapper(), redis, new SimpleMeterRegistry(),
                ObservationRegistry.create(), props, kb);

        List<AgentEvent> events = new ArrayList<>();
        AgentExecutionResult result = service.start(1L, "session", "cap-1", "问题", 3L, events::add);

        assertEquals("MAX_STEPS_REACHED", result.status());
        org.mockito.Mockito.verify(executor, org.mockito.Mockito.times(2))
                .execute(any(Long.class), any(Long.class), anyString(), any());
    }

    private static AgentRunService service(ChatModelClient chatClient) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "agent-run-test"),
                com.rag2agent.bootstrap.entity.AgentRun.class);
        AgentRunMapper runMapper = (AgentRunMapper) Proxy.newProxyInstance(
                AgentRunMapper.class.getClassLoader(),
                new Class<?>[] {AgentRunMapper.class},
                (proxy, method, args) -> method.getReturnType() == int.class ? 1 : null);
        AgentStepMapper stepMapper = new AgentStepMapper() {
            @Override
            public int insert(AgentStep step) {
                return 1;
            }

            @Override
            public List<AgentStep> listByRunId(Long runId) {
                return List.of();
            }
        };
        return new AgentRunService(
                chatClient,
                null,
                null,
                null,
                runMapper,
                stepMapper,
                null,
                new ObjectMapper(),
                null,
                new SimpleMeterRegistry(),
                ObservationRegistry.create(),
                new AgentProperties(),
                null);
    }

    private static AgentRunService serviceWith(AgentRunMapper runs, StringRedisTemplate redis) {
        return serviceWith(runs, redis, new SimpleMeterRegistry());
    }

    private static AgentRunService serviceWith(
            AgentRunMapper runs, StringRedisTemplate redis, MeterRegistry registry) {
        AgentStepMapper steps = mock(AgentStepMapper.class);
        ToolCallRecordMapper calls = mock(ToolCallRecordMapper.class);
        when(calls.listByRunId(42L)).thenReturn(List.of());
        com.rag2agent.bootstrap.service.KnowledgeBaseService knowledge = mock(
                com.rag2agent.bootstrap.service.KnowledgeBaseService.class);
        return new AgentRunService(
                chatClient(request -> { throw new AssertionError("模型不应被调用"); }),
                null, null, null, runs, steps, calls, new ObjectMapper(), redis,
                registry, ObservationRegistry.create(), new AgentProperties(), knowledge);
    }

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate redisWithReferences() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(any(String.class), any(String.class), any())).thenReturn(true);
        when(values.get("agent:references:41")).thenReturn("[]");
        when(values.get("agent:references:42")).thenReturn("[]");
        return redis;
    }

    private static ChatModelClient chatClient(
            java.util.function.Function<ChatCompletionRequest, ChatCompletionResponse> complete) {
        return new ChatModelClient() {
            @Override
            public ChatCompletionResponse complete(ChatCompletionRequest request) {
                return complete.apply(request);
            }

            @Override
            public void stream(ChatCompletionRequest request, Consumer<String> onDelta) {}
        };
    }
}
