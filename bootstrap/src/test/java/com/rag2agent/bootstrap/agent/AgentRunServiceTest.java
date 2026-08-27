package com.rag2agent.bootstrap.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.rag2agent.bootstrap.config.AgentProperties;
import com.rag2agent.bootstrap.entity.AgentStep;
import com.rag2agent.bootstrap.mapper.AgentRunMapper;
import com.rag2agent.bootstrap.mapper.AgentStepMapper;
import com.rag2agent.infra.ai.client.ChatModelClient;
import com.rag2agent.infra.ai.exception.AiClientException;
import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import com.rag2agent.infra.ai.model.ChatMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;

class AgentRunServiceTest {

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
