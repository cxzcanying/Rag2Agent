package com.rag2agent.bootstrap.agent;

import com.rag2agent.infra.ai.model.ChatMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * 在不额外调用模型的前提下压缩 Agent 消息历史。
 * 估算偏保守，优先保留系统约束、原始问题和最近一轮工具交互。
 */
public class ContextCompactor {

    private static final String SUMMARY_PREFIX = "[上下文压缩摘要]\n";

    private final int summaryMaxChars;

    public ContextCompactor(int summaryMaxChars) {
        if (summaryMaxChars < 100) {
            throw new IllegalArgumentException("summaryMaxChars 必须至少为 100");
        }
        this.summaryMaxChars = summaryMaxChars;
    }

    public CompactionResult compact(List<ChatMessage> messages, int tokenBudget) {
        if (tokenBudget < 256) {
            throw new IllegalArgumentException("tokenBudget 必须至少为 256");
        }
        int before = estimateTokens(messages);
        if (before <= tokenBudget) {
            return new CompactionResult(List.copyOf(messages), before, before, 0, false);
        }

        List<ChatMessage> preserved = new ArrayList<>();
        ChatMessage system = messages.stream()
                .filter(message -> "system".equals(message.role()))
                .findFirst()
                .orElse(null);
        if (system != null) {
            preserved.add(system);
        }
        ChatMessage firstUser = messages.stream()
                .filter(message -> "user".equals(message.role()))
                .findFirst()
                .orElse(null);
        if (firstUser != null && firstUser != system) {
            preserved.add(firstUser);
        }

        int tailStart = messages.size();
        int used = estimateTokens(preserved);
        while (tailStart > 0) {
            ChatMessage candidate = messages.get(tailStart - 1);
            int candidateTokens = estimateTokens(List.of(candidate));
            if (used + candidateTokens > tokenBudget - 80) {
                break;
            }
            tailStart--;
            used += candidateTokens;
        }
        // tool 消息必须跟在对应 assistant tool_calls 后面，避免压缩后请求被 provider 拒绝。
        if (tailStart > 0 && tailStart < messages.size() && "tool".equals(messages.get(tailStart).role())) {
            tailStart--;
        }

        List<ChatMessage> dropped = messages.subList(0, tailStart);
        String summary = summarize(dropped);
        if (!summary.isBlank()) {
            preserved.add(new ChatMessage("user", SUMMARY_PREFIX + summary));
        }
        preserved.addAll(messages.subList(tailStart, messages.size()));

        List<ChatMessage> compacted = trimToBudget(preserved, tokenBudget);
        int after = estimateTokens(compacted);
        int retainedOriginalMessages = (int) compacted.stream()
                .filter(compactedMessage -> messages.stream().anyMatch(original -> original == compactedMessage))
                .count();
        return new CompactionResult(
                compacted, before, after, messages.size() - retainedOriginalMessages, true);
    }

    public int estimateTokens(List<ChatMessage> messages) {
        return messages.stream().mapToInt(this::estimateTokens).sum();
    }

    private int estimateTokens(ChatMessage message) {
        int chars = message.content() == null ? 0 : message.content().length();
        if (message.toolCalls() != null) {
            chars += message.toolCalls().stream()
                    .mapToInt(call -> (call.name() == null ? 0 : call.name().length())
                            + (call.arguments() == null ? 0 : call.arguments().length()) + 20)
                    .sum();
        }
        return 4 + Math.max(1, (chars + 1) / 2);
    }

    private String summarize(List<ChatMessage> dropped) {
        StringBuilder summary = new StringBuilder();
        for (ChatMessage message : dropped) {
            String content = message.content();
            if (content == null || content.isBlank()) {
                continue;
            }
            if (summary.length() > 0) {
                summary.append(" | ");
            }
            summary.append(message.role()).append(": ").append(content.replaceAll("\\s+", " "));
            if (summary.length() >= summaryMaxChars) {
                break;
            }
        }
        return summary.length() <= summaryMaxChars
                ? summary.toString()
                : summary.substring(0, summaryMaxChars);
    }

    private List<ChatMessage> trimToBudget(List<ChatMessage> messages, int tokenBudget) {
        List<ChatMessage> result = new ArrayList<>(messages);
        while (estimateTokens(result) > tokenBudget && result.size() > 2) {
            // 保留 system、首个 user 和尾部上下文，优先移除最早的中间消息。
            ChatMessage removed = result.remove(2);
            if (removed.toolCalls() != null && !removed.toolCalls().isEmpty()) {
                while (result.size() > 2 && "tool".equals(result.get(2).role())) {
                    result.remove(2);
                }
            }
        }
        if (estimateTokens(result) > tokenBudget && result.size() >= 1) {
            int preservedTokens = result.size() == 1 ? 0 : estimateTokens(List.of(result.getFirst()));
            int contentChars = Math.max(100, (tokenBudget - preservedTokens - 8) * 2);
            int targetIndex = result.size() == 1 ? 0 : 1;
            ChatMessage target = result.get(targetIndex);
            result.set(targetIndex, withContent(target, truncateMiddle(target.content(), contentChars)));
        }
        return List.copyOf(result);
    }

    private static ChatMessage withContent(ChatMessage message, String content) {
        return new ChatMessage(
                message.role(), content, message.name(), message.toolCallId(), message.toolCalls());
    }

    private static String truncateMiddle(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        String marker = "\n...[内容已截断]...\n";
        int remaining = Math.max(0, maxChars - marker.length());
        int head = remaining * 2 / 3;
        return value.substring(0, head) + marker + value.substring(value.length() - (remaining - head));
    }

    public record CompactionResult(
            List<ChatMessage> messages,
            int estimatedTokensBefore,
            int estimatedTokensAfter,
            int droppedMessages,
            boolean compacted) {}
}
