package com.rag2agent.rag.core.split.impl;

import com.rag2agent.rag.core.document.ParsedDocument;
import com.rag2agent.rag.core.split.TextChunk;
import com.rag2agent.rag.core.split.TextSplitter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 递归切块：段落优先（空行分隔）→ 句子边界（中文/英文标点）→ 固定窗口兜底。
 * 相邻 chunk 之间带 overlap，保留上下文连续性。
 * 参数（chunkSize / overlap）后续由评测实验确定，当前为初版默认值。
 *
 * <p>为什么是"段落 → 句子 → 窗口"三级：
 * 段落是语义最完整的最小单元，优先保留；段落超长时按句子切，避免把一句话从中间截断；
 * 遇到无标点的超长文本（如代码/URL）才用固定窗口机械兜底，保证任何输入都能切完。
 * @author 21311
 */
public class RecursiveTextSplitter implements TextSplitter {

    private final int chunkSize;
    private final int overlap;

    public RecursiveTextSplitter() {
        this(800, 100);
    }

    public RecursiveTextSplitter(int chunkSize, int overlap) {
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("chunkSize 必须大于 0，overlap 必须小于 chunkSize");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<TextChunk> split(ParsedDocument document) {
        List<TextChunk> chunks = new ArrayList<>();
        // position 是全局序号，chunk id 用 "文档id-序号" 保证唯一且有序
        int position = 0;
        // 第一层：按空行把全文切成段落，逐段处理
        for (String paragraph : splitByBlankLines(document.text())) {
            // 第二层：段落内按句子/窗口切成不超过 chunkSize 的小块
            for (String piece : splitText(paragraph)) {
                String content = piece;
                // overlap：把上一块的尾部字符拼到当前块开头。
                // 作用：跨块被打断的句子/概念，下一块仍带着上一块的结尾，检索时上下文不丢
                if (overlap > 0 && !chunks.isEmpty()) {
                    String previous = chunks.getLast().content();
                    String tail = previous.substring(Math.max(0, previous.length() - overlap));
                    content = tail + content;
                }
                chunks.add(new TextChunk(
                        document.documentId() + "-" + position,
                        document.documentId(),
                        content,
                        position++,
                        Map.of()));
            }
        }
        return chunks;
    }

    /**
     * 按空行切段落：\n\s*\n 匹配"一个或多个空行"。
     * 空行是文本的段落边界，段落内保持连续，段落间语义相对独立。
     */
    private List<String> splitByBlankLines(String text) {
        return Arrays.stream(text.split("\\n\\s*\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 把段落切成不超过 chunkSize 的小块。
     * 策略：先按句子边界累加（尽量塞满一整句），超长句子再按固定窗口兜底。
     */
    private List<String> splitText(String text) {
        List<String> pieces = new ArrayList<>();
        // 整段没超限：一块直接返回，语义最完整
        if (text.length() <= chunkSize) {
            pieces.add(text);
            return pieces;
        }
        StringBuilder current = new StringBuilder();
        // (?<=[。！？!?；;]) 是"零宽后行断言"：在标点之后切分但不吞掉标点，
        // 保证每个句子结束符留在句尾，切出来的小块仍能读
        for (String sentence : text.split("(?<=[。！？!?；;])")) {
            if (sentence.isBlank()) {
                continue;
            }
            // 当前累计 + 下一句会超限：先把已累计的句子作为一块输出，再重新累计
            if (current.length() + sentence.length() > chunkSize && current.length() > 0) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            // 单句本身超长（无标点的连续文本）：按 chunkSize 固定窗口切，机械兜底
            if (sentence.length() > chunkSize) {
                for (int i = 0; i < sentence.length(); i += chunkSize) {
                    pieces.add(sentence.substring(i, Math.min(sentence.length(), i + chunkSize)));
                }
            } else {
                current.append(sentence);
            }
        }
        // 最后一批没塞满 chunkSize 的剩余内容也要输出，否则会丢文本
        if (!current.isEmpty()) {
            pieces.add(current.toString());
        }
        return pieces;
    }
}
