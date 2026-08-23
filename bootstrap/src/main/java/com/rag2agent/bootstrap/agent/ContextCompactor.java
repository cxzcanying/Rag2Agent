package com.rag2agent.bootstrap.agent;

import com.rag2agent.infra.ai.model.ChatMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 上下文压缩器
 *
 * 作用：当 Agent 的对话历史太长，超过 LLM 的上下文窗口限制时，
 * 在不额外调用模型的前提下，智能压缩历史消息。
 *
 * 压缩策略（保守且安全）：
 * 1. 优先保留：系统提示词（system）、用户的第一个问题（user）
 * 2. 保留尾部最近的对话（最新的几条消息）
 * 3. 被丢弃的中间消息会生成一个文本摘要，以 user 消息插入
 * 4. 保证 tool 消息不会和对应的 tool_calls 分离（避免 API 报错）
 *
 * 估算规则：偏保守估算，每个 token 约 2 个字符 + 消息固定开销
 *
 * @author 21311
 */
public class ContextCompactor {

    /**
     * 摘要信息的前缀标记
     * 让模型知道这部分内容是"压缩后的历史摘要"，而不是真实的用户消息
     */
    private static final String SUMMARY_PREFIX = "[上下文压缩摘要]\n";

    /**
     * 摘要内容的最大字符数
     * 控制摘要的长度，避免摘要本身占用过多 token
     */
    private final int summaryMaxChars;

    /**
     * 构造函数
     *
     * @param summaryMaxChars 摘要最大字符数，至少 100
     */
    public ContextCompactor(int summaryMaxChars) {
        if (summaryMaxChars < 100) {
            throw new IllegalArgumentException("summaryMaxChars 必须至少为 100");
        }
        this.summaryMaxChars = summaryMaxChars;
    }

    // ==================== 核心方法 ====================

    /**
     * 压缩消息列表，使其 Token 总数不超过预算
     *
     * 这是整个类的核心方法，执行流程：
     * 1. 估算当前总 Token 数，如果未超预算 → 直接返回
     * 2. 超预算 → 开始压缩：
     *    a. 提取 system 消息和第一个 user 消息（必须保留）
     *    b. 从尾部往前添加消息（保留最近的内容）
     *    c. 对丢弃的消息生成文本摘要
     *    d. 组装压缩后的消息列表
     *    e. 如果仍然超预算，强制截断最长的消息内容
     *
     * @param messages    原始消息列表（不会被修改）
     * @param tokenBudget Token 预算（如 8192）
     * @return 压缩结果，包含压缩后的消息列表和统计数据
     */
    public CompactionResult compact(List<ChatMessage> messages, int tokenBudget) {
        // 参数校验：预算至少 256 token
        if (tokenBudget < 256) {
            throw new IllegalArgumentException("tokenBudget 必须至少为 256");
        }

        // 1. 估算当前 token 数
        int before = estimateTokens(messages);

        // 2. 如果未超预算，直接返回原始消息（不压缩）
        if (before <= tokenBudget) {
            return new CompactionResult(List.copyOf(messages), before, before, 0, false);
        }

        // ===== 开始压缩 =====

        List<ChatMessage> preserved = new ArrayList<>();

        // 3a. 优先保留 system 消息（系统提示词，最重要）
        ChatMessage system = messages.stream()
                .filter(message -> "system".equals(message.role()))
                .findFirst()
                .orElse(null);
        if (system != null) {
            preserved.add(system);
        }

        // 3b. 保留第一个 user 消息（用户的原始问题）
        ChatMessage firstUser = messages.stream()
                .filter(message -> "user".equals(message.role()))
                .findFirst()
                .orElse(null);
        if (firstUser != null && firstUser != system) {
            preserved.add(firstUser);
        }

        // 3c. 从尾部往前遍历，尽量保留最近的消息
        int tailStart = messages.size();          // tailStart 标记"从哪开始保留尾部消息"
        int used = estimateTokens(preserved);     // 已使用的 token 数

        while (tailStart > 0) {
            ChatMessage candidate = messages.get(tailStart - 1);
            int candidateTokens = estimateTokens(List.of(candidate));

            // 如果加上这条消息会超预算（预留 80 token 的安全边界），停止
            if (used + candidateTokens > tokenBudget - 80) {
                break;
            }
            tailStart--;
            used += candidateTokens;
        }

        /**
         * 重要：工具消息的完整性保护
         *
         * tool 消息必须跟在对应的 assistant（含 tool_calls）后面
         * 如果压缩边界正好切在 tool 消息上，会导致：
         *   assistant: 我要调用 search 工具，tool_call_id=123
         *   [压缩边界切在这里 ↓]
         *   tool: 搜索结果：...  ← 这条被保留了，但对应的 tool_call 被丢弃了
         *
         * API 会报错："tool 消息缺少对应的 tool_calls"
         *
         * 解决方案：如果边界后面第一条消息是 tool，就把边界往前移，
         * 让 tool 消息也进入"被丢弃"区域，不保留孤立的 tool 消息
         */
        if (tailStart > 0 && tailStart < messages.size() && "tool".equals(messages.get(tailStart).role())) {
            tailStart--;
        }

        // 3d. 对丢弃的消息（索引 0 到 tailStart-1）生成摘要
        List<ChatMessage> dropped = messages.subList(0, tailStart);
        String summary = summarize(dropped);
        if (!summary.isBlank()) {
            // 将摘要作为一条 user 消息插入，前缀标记让模型知道这是压缩摘要
            preserved.add(new ChatMessage("user", SUMMARY_PREFIX + summary));
        }

        // 3e. 追加保留的尾部消息
        preserved.addAll(messages.subList(tailStart, messages.size()));

        // 3f. 二次保险：如果仍然超预算，强制截断
        List<ChatMessage> compacted = trimToBudget(preserved, tokenBudget);

        // 3g. 统计信息
        int after = estimateTokens(compacted);
        int retainedOriginalMessages = (int) compacted.stream()
                .filter(compactedMessage -> messages.stream().anyMatch(original -> original == compactedMessage))
                .count();

        return new CompactionResult(
                compacted, before, after, messages.size() - retainedOriginalMessages, true);
    }

    // ==================== Token 估算 ====================

    /**
     * 估算多条消息的总 Token 数
     *
     * @param messages 消息列表
     * @return 估算的 Token 数
     */
    public int estimateTokens(List<ChatMessage> messages) {
        return messages.stream().mapToInt(this::estimateTokens).sum();
    }

    /**
     * 估算单条消息的 Token 数
     *
     * 估算公式（偏保守）：
     * - 基础开销：4 token（消息结构）
     * - 内容：每 2 个字符 ≈ 1 token
     * - tool_calls：额外计算工具名和参数长度
     *
     * 为什么偏保守？（估算值 > 实际值）
     * 因为压缩宁可多压缩一点，也不能让实际 token 超限导致 API 报错
     *
     * @param message 单条消息
     * @return 估算的 Token 数
     */
    private int estimateTokens(ChatMessage message) {
        // 计算内容字符数
        int chars = message.content() == null ? 0 : message.content().length();

        // 如果有 tool_calls，额外计算工具调用占用的字符
        if (message.toolCalls() != null) {
            chars += message.toolCalls().stream()
                    .mapToInt(call -> (call.name() == null ? 0 : call.name().length())
                            + (call.arguments() == null ? 0 : call.arguments().length()) + 20)
                    .sum();
        }

        // 4 token 基础开销 + (字符数 / 2) 向上取整
        return 4 + Math.max(1, (chars + 1) / 2);
    }

    // ==================== 摘要生成 ====================

    /**
     * 对丢弃的消息生成文本摘要
     *
     * 策略：简单拼接每条消息的 "角色: 内容"，用 " | " 分隔
     * 限制摘要长度不超过 summaryMaxChars
     *
     * 注意：这是"文本摘要"而非"语义摘要"，
     * 只是把压缩掉的消息内容罗列出来，让模型知道"之前聊过什么"
     *
     * @param dropped 被丢弃的消息列表
     * @return 摘要文本
     */
    private String summarize(List<ChatMessage> dropped) {
        StringBuilder summary = new StringBuilder();

        for (ChatMessage message : dropped) {
            String content = message.content();
            if (content == null || content.isBlank()) {
                continue;
            }

            // 用 " | " 分隔不同消息
            if (!summary.isEmpty()) {
                summary.append(" | ");
            }

            // 格式：角色: 内容（去除多余空白）
            summary.append(message.role()).append(": ").append(content.replaceAll("\\s+", " "));

            // 如果摘要已达到长度限制，停止拼接
            if (summary.length() >= summaryMaxChars) {
                break;
            }
        }

        // 截断到指定长度
        return summary.length() <= summaryMaxChars
                ? summary.toString()
                : summary.substring(0, summaryMaxChars);
    }

    // ==================== 强制截断（最后保险） ====================

    /**
     * 强制将消息列表压缩到 Token 预算以内
     *
     * 这是最后的"暴力"手段，当上面的压缩策略仍然无法满足预算时：
     * 1. 优先移除最早的"中间消息"（保留 system、首个 user、尾部）
     * 2. 如果移除消息时连带 tool_calls，也一并移除对应的 tool 响应
     * 3. 如果消息数量太少（≤2），截断最长的消息内容
     *
     * @param messages    当前压缩后的消息列表
     * @param tokenBudget Token 预算
     * @return 强制压缩后的消息列表
     */
    private List<ChatMessage> trimToBudget(List<ChatMessage> messages, int tokenBudget) {
        List<ChatMessage> result = new ArrayList<>(messages);

        // 循环：只要超预算且还有可移除的消息（至少保留 2 条）
        while (estimateTokens(result) > tokenBudget && result.size() > 2) {
            // 移除索引 2 的消息（即最早的"中间消息"）
            // 索引 0: system, 索引 1: 首个 user 或摘要, 索引 2+: 中间消息
            ChatMessage removed = result.remove(2);

            // 如果移除的是 assistant（含 tool_calls），
            // 需要同时移除后续对应的 tool 响应消息
            if (removed.toolCalls() != null && !removed.toolCalls().isEmpty()) {
                while (result.size() > 2 && "tool".equals(result.get(2).role())) {
                    result.remove(2);
                }
            }
        }

        /**
         * 如果消息只剩 1-2 条但仍然超预算（极端情况）：
         * 截断消息内容，而不是继续移除（不能再少了）
         */
        if (estimateTokens(result) > tokenBudget && result.size() >= 1) {
            // 计算除目标消息外，其他消息占用的 token
            int preservedTokens = result.size() == 1 ? 0 : estimateTokens(List.of(result.getFirst()));

            // 目标消息可用的字符数 = (预算 - 其他消息占用 - 8) × 2
            int contentChars = Math.max(100, (tokenBudget - preservedTokens - 8) * 2);

            // 目标索引：如果只有 1 条消息，截断它；否则截断索引 1（首个 user）
            int targetIndex = result.size() == 1 ? 0 : 1;
            ChatMessage target = result.get(targetIndex);

            // 替换为截断后的内容
            result.set(targetIndex, withContent(target, truncateMiddle(target.content(), contentChars)));
        }

        return List.copyOf(result);
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建一个新的 ChatMessage，只替换 content，其他字段不变
     */
    private static ChatMessage withContent(ChatMessage message, String content) {
        return new ChatMessage(
                message.role(), content, message.name(), message.toolCallId(), message.toolCalls());
    }

    /**
     * 从中间截断文本，保留开头和结尾
     *
     * 例如：原始文本 1000 字符，maxChars=200
     * 结果："开头 133 字符...[内容已截断]...结尾 67 字符"
     *
     * 这样比"从尾部截断"更好，因为保留了开头（可能有关键上下文）
     * 和结尾（可能有关键结论）
     *
     * @param value    原始文本
     * @param maxChars 最大字符数
     * @return 截断后的文本
     */
    private static String truncateMiddle(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }

        String marker = "\n...[内容已截断]...\n";
        int remaining = Math.max(0, maxChars - marker.length());
        // 2/3 给开头，1/3 给结尾
        int head = remaining * 2 / 3;

        return value.substring(0, head) + marker + value.substring(value.length() - (remaining - head));
    }

    // ==================== 结果记录类 ====================

    /**
     * 压缩结果记录
     *
     * @param messages             压缩后的消息列表
     * @param estimatedTokensBefore  压缩前估算 Token 数
     * @param estimatedTokensAfter   压缩后估算 Token 数
     * @param droppedMessages        被丢弃的消息数量
     * @param compacted              是否进行了压缩
     */
    public record CompactionResult(
            List<ChatMessage> messages,
            int estimatedTokensBefore,
            int estimatedTokensAfter,
            int droppedMessages,
            boolean compacted) {}
}