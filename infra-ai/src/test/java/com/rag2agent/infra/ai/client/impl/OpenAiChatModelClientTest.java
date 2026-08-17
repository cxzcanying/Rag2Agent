package com.rag2agent.infra.ai.client.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rag2agent.infra.ai.config.AiProviderProperties.Provider;
import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import com.rag2agent.infra.ai.model.ChatMessage;
import com.rag2agent.infra.ai.model.ToolCall;
import com.rag2agent.infra.ai.model.ToolDef;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiChatModelClientTest {

    private MockWebServer server;
    private OpenAiChatModelClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        Provider provider = new Provider();
        provider.setBaseUrl(server.url("/").toString());
        provider.setApiKey("test-key");
        provider.setChatModel("test-chat-model");
        client = new OpenAiChatModelClient(provider);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void complete_parsesResponse() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "choices": [{
                            "message": {"role": "assistant", "content": "你好"},
                            "finish_reason": "stop"
                          }],
                          "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                        }
                        """));

        ChatCompletionResponse response = client.complete(new ChatCompletionRequest(
                "deepseek", null, List.of(new ChatMessage("user", "hello")), Map.of()));

        assertEquals("你好", response.content());
        assertEquals("stop", response.finishReason());
        assertEquals(15, response.usage().get("total_tokens"));
    }

    @Test
    void complete_usesRequestModelWhenPresent() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"));

        client.complete(new ChatCompletionRequest(
                "deepseek", "custom-model", List.of(new ChatMessage("user", "hi")), Map.of()));

        String sent = server.takeRequest().getBody().readUtf8();
        assertNotNull(sent);
        assertEquals(true, sent.contains("\"model\":\"custom-model\""));
    }

    @Test
    void complete_parsesToolCalls() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "choices": [{
                            "message": {
                              "role": "assistant",
                              "content": null,
                              "tool_calls": [{
                                "id": "call_1",
                                "type": "function",
                                "function": {"name": "delete_document", "arguments": "{\\"document_id\\":123}"}
                              }]
                            },
                            "finish_reason": "tool_calls"
                          }],
                          "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                        }
                        """));

        ChatCompletionResponse response = client.complete(new ChatCompletionRequest(
                "deepseek", null, List.of(new ChatMessage("user", "删除文档 123")), Map.of()));

        assertEquals("tool_calls", response.finishReason());
        assertEquals(1, response.toolCalls().size());
        assertEquals("delete_document", response.toolCalls().getFirst().name());
        assertEquals("{\"document_id\":123}", response.toolCalls().getFirst().arguments());
    }

    @Test
    void complete_serializesToolsAndToolResult() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}"));

        List<ChatMessage> messages = List.of(
                new ChatMessage("user", "hi"),
                ChatMessage.assistantWithToolCalls(List.of(
                        new ToolCall("call_1", "function", "delete_document", "{\"document_id\":123}"))),
                ChatMessage.tool("call_1", "delete_document", "ok"));
        List<ToolDef> tools = List.of(new ToolDef("delete_document", "删除文档", Map.of("type", "object")));

        client.complete(new ChatCompletionRequest("deepseek", null, messages, Map.of(), tools));

        String sent = server.takeRequest().getBody().readUtf8();
        assertTrue(sent.contains("\"tools\""), "请求应包含 tools 声明");
        assertTrue(sent.contains("\"tool_calls\""), "请求应包含 assistant 的 tool_calls");
        assertTrue(sent.contains("\"tool_call_id\":\"call_1\""), "请求应包含 tool 消息的 tool_call_id");
        assertTrue(sent.contains("\"name\":\"delete_document\""), "请求应包含工具名");
    }

    @Test
    void stream_collectsDeltas() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}

                        data: {"choices":[{"delta":{"content":"你"},"finish_reason":null}]}

                        data: {"choices":[{"delta":{"content":"好"},"finish_reason":null}]}

                        data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                        data: [DONE]
                        """));

        StringBuilder collected = new StringBuilder();
        client.stream(new ChatCompletionRequest(
                        "deepseek", null, List.of(new ChatMessage("user", "hello")), Map.of()),
                collected::append);

        assertEquals("你好", collected.toString());
    }
}
