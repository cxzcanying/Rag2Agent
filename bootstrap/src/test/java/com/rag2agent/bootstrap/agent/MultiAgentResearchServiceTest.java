package com.rag2agent.bootstrap.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.rag2agent.bootstrap.config.AgentProperties;
import com.rag2agent.bootstrap.dto.AgentDtos.TeamResearchRequest;
import com.rag2agent.bootstrap.entity.AgentRun;
import com.rag2agent.bootstrap.mapper.AgentRunMapper;
import com.rag2agent.bootstrap.service.HybridSearchService;
import com.rag2agent.bootstrap.service.KnowledgeBaseService;
import com.rag2agent.infra.ai.client.ChatModelClient;
import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;

class MultiAgentResearchServiceTest {

    @Test
    void coordinatorRunsRetrievalAnalysisAndReviewer() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "multi-agent-test"),
                AgentRun.class);
        AgentRunMapper runs = mock(AgentRunMapper.class);
        when(runs.insert(any(AgentRun.class))).thenAnswer(invocation -> {
            ((AgentRun) invocation.getArgument(0)).setId(7L);
            return 1;
        });
        AgentTaskService tasks = mock(AgentTaskService.class);
        AtomicInteger ids = new AtomicInteger();
        when(tasks.create(anyLong(), any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> (long) ids.incrementAndGet());
        when(tasks.start(anyLong(), anyString())).thenReturn(1);
        when(tasks.complete(anyLong(), any())).thenReturn(1);
        HybridSearchService search = mock(HybridSearchService.class);
        when(search.search(3L, "比较营收", 8)).thenReturn(List.of(
                new RetrievalResult("c1", "营收增长 10%", 0.9,
                        Map.of("documentId", 1, "chunkIndex", 0))));
        ChatModelClient chat = new ChatModelClient() {
            @Override
            public String providerName() {
                return "test";
            }

            @Override
            public String modelName() {
                return "test";
            }

            @Override
            public ChatCompletionResponse complete(ChatCompletionRequest request) {
                String system = request.messages().getFirst().content();
                String content = system.contains("复核 Agent")
                        ? "{\"approved\":true,\"answer\":\"营收增长 10% [1]\"}"
                        : "营收增长 10% [1]";
                return new ChatCompletionResponse(content, "stop", Map.of());
            }

            @Override
            public void stream(
                    ChatCompletionRequest request,
                    java.util.function.Consumer<String> onDelta) {
                throw new UnsupportedOperationException();
            }
        };
        MultiAgentResearchService service = new MultiAgentResearchService(
                runs, tasks, search, chat, mock(KnowledgeBaseService.class), new ObjectMapper(),
                new AgentProperties(), Runnable::run);

        var result = service.research(2L, new TeamResearchRequest(3L, "比较营收"));

        assertEquals("COMPLETED", result.status());
        assertEquals("营收增长 10% [1]", result.answer());
        assertEquals(1, result.references().size());
    }

    @Test
    void analystWorkersRunConcurrentlyBeforeReviewer() throws Exception {
        AgentRunMapper runs = mock(AgentRunMapper.class);
        when(runs.insert(any(AgentRun.class))).thenAnswer(invocation -> {
            ((AgentRun) invocation.getArgument(0)).setId(8L);
            return 1;
        });
        AgentTaskService tasks = mock(AgentTaskService.class);
        AtomicInteger ids = new AtomicInteger();
        when(tasks.create(anyLong(), any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> (long) ids.incrementAndGet());
        when(tasks.start(anyLong(), anyString())).thenReturn(1);
        when(tasks.complete(anyLong(), any())).thenReturn(1);
        HybridSearchService search = mock(HybridSearchService.class);
        when(search.search(3L, "分析营收", 8)).thenReturn(List.of(
                new RetrievalResult("c1", "营收增长 10%", 0.9,
                        Map.of("documentId", 1, "chunkIndex", 0))));
        CountDownLatch analystsStarted = new CountDownLatch(2);
        ChatModelClient chat = new ChatModelClient() {
            @Override
            public String providerName() {
                return "test";
            }

            @Override
            public String modelName() {
                return "test";
            }

            @Override
            public ChatCompletionResponse complete(ChatCompletionRequest request) {
                String system = request.messages().getFirst().content();
                if (system.contains("独立分析 Agent")) {
                    analystsStarted.countDown();
                    try {
                        assertTrue(analystsStarted.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return new ChatCompletionResponse("分析结果 [1]", "stop", Map.of());
                }
                return new ChatCompletionResponse(
                        "{\"approved\":true,\"answer\":\"并行结果 [1]\"}", "stop", Map.of());
            }

            @Override
            public void stream(
                    ChatCompletionRequest request,
                    java.util.function.Consumer<String> onDelta) {
                throw new UnsupportedOperationException();
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            MultiAgentResearchService service = new MultiAgentResearchService(
                    runs, tasks, search, chat, mock(KnowledgeBaseService.class), new ObjectMapper(),
                    new AgentProperties(), executor);

            var result = service.research(2L, new TeamResearchRequest(3L, "分析营收"));

            assertEquals("COMPLETED", result.status());
            assertEquals("并行结果 [1]", result.answer());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void oneAnalystFailureIsIsolatedAndReturnsDegradedAnswer() {
        AgentRunMapper runs = mock(AgentRunMapper.class);
        when(runs.insert(any(AgentRun.class))).thenAnswer(invocation -> {
            ((AgentRun) invocation.getArgument(0)).setId(9L);
            return 1;
        });
        AgentTaskService tasks = mock(AgentTaskService.class);
        AtomicInteger ids = new AtomicInteger();
        when(tasks.create(anyLong(), any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> (long) ids.incrementAndGet());
        when(tasks.start(anyLong(), anyString())).thenReturn(1);
        when(tasks.complete(anyLong(), any())).thenReturn(1);
        HybridSearchService search = mock(HybridSearchService.class);
        when(search.search(3L, "分析风险", 8)).thenReturn(List.of(
                new RetrievalResult("c1", "存在供应风险", 0.9,
                        Map.of("documentId", 1, "chunkIndex", 0))));
        ChatModelClient chat = new ChatModelClient() {
            @Override
            public String providerName() {
                return "test";
            }

            @Override
            public String modelName() {
                return "test";
            }

            @Override
            public ChatCompletionResponse complete(ChatCompletionRequest request) {
                String system = request.messages().getFirst().content();
                if (system.contains("提取可验证事实")) {
                    throw new IllegalStateException("分析模型超时");
                }
                if (system.contains("独立分析 Agent")) {
                    return new ChatCompletionResponse("存在供应风险 [1]", "stop", Map.of());
                }
                return new ChatCompletionResponse(
                        "{\"approved\":true,\"answer\":\"存在供应风险 [1]\"}", "stop", Map.of());
            }

            @Override
            public void stream(
                    ChatCompletionRequest request,
                    java.util.function.Consumer<String> onDelta) {
                throw new UnsupportedOperationException();
            }
        };
        MultiAgentResearchService service = new MultiAgentResearchService(
                runs, tasks, search, chat, mock(KnowledgeBaseService.class), new ObjectMapper(),
                new AgentProperties(), Runnable::run);

        var result = service.research(2L, new TeamResearchRequest(3L, "分析风险"));

        assertEquals("DEGRADED", result.status());
        assertEquals("存在供应风险 [1]", result.answer());
    }
}
