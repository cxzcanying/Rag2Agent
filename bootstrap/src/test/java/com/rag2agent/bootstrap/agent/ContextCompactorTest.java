package com.rag2agent.bootstrap.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rag2agent.infra.ai.model.ChatMessage;
import com.rag2agent.infra.ai.model.ToolCall;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextCompactorTest {

    private final ContextCompactor compactor = new ContextCompactor(200);

    @Test
    void keepsSystemAndRecentMessagesWhenBudgetExceeded() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", "系统约束"));
        messages.add(new ChatMessage("user", "原始问题"));
        for (int i = 0; i < 12; i++) {
            messages.add(new ChatMessage("assistant", "历史回答".repeat(20)));
        }

        ContextCompactor.CompactionResult result = compactor.compact(messages, 256);

        assertTrue(result.compacted());
        assertTrue(result.estimatedTokensAfter() <= 256);
        assertTrue("system".equals(result.messages().getFirst().role()));
        assertTrue(result.messages().stream().anyMatch(message ->
                message.content() != null && message.content().contains("上下文压缩摘要")));
    }

    @Test
    void doesNotChangeShortContext() {
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", "系统"), new ChatMessage("user", "问题"));

        ContextCompactor.CompactionResult result = compactor.compact(messages, 256);

        assertTrue(!result.compacted());
        assertTrue(result.messages().equals(messages));
    }

    @Test
    void truncatesSingleOversizedUserMessageWithinBudget() {
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", "系统约束"),
                new ChatMessage("user", "很长的问题".repeat(1000)));

        ContextCompactor.CompactionResult result = compactor.compact(messages, 256);

        assertTrue(result.estimatedTokensAfter() <= 256);
        assertTrue(result.messages().get(1).content().contains("内容已截断"));
    }

    @Test
    void neverKeepsToolResultWithoutAssistantToolCall() {
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", "系统约束"),
                new ChatMessage("user", "删除文档"),
                new ChatMessage("assistant", "历史".repeat(300)),
                ChatMessage.assistantWithToolCalls(List.of(
                        new ToolCall("call-1", "function", "delete_document", "{\"document_id\":1}"))),
                ChatMessage.tool("call-1", "delete_document", "已删除"));

        ContextCompactor.CompactionResult result = compactor.compact(messages, 256);
        int toolIndex = -1;
        for (int i = 0; i < result.messages().size(); i++) {
            if ("tool".equals(result.messages().get(i).role())) {
                toolIndex = i;
            }
        }

        assertTrue(toolIndex < 0 || (toolIndex > 0
                && result.messages().get(toolIndex - 1).toolCalls() != null
                && !result.messages().get(toolIndex - 1).toolCalls().isEmpty()));
    }
}
