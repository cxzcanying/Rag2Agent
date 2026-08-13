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
        int position = 0;
        for (String paragraph : splitByBlankLines(document.text())) {
            for (String piece : splitText(paragraph)) {
                String content = piece;
                if (overlap > 0 && !chunks.isEmpty()) {
                    String previous = chunks.get(chunks.size() - 1).content();
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

    private List<String> splitByBlankLines(String text) {
        return Arrays.stream(text.split("\\n\\s*\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private List<String> splitText(String text) {
        List<String> pieces = new ArrayList<>();
        if (text.length() <= chunkSize) {
            pieces.add(text);
            return pieces;
        }
        StringBuilder current = new StringBuilder();
        for (String sentence : text.split("(?<=[。！？!?；;])")) {
            if (sentence.isBlank()) {
                continue;
            }
            if (current.length() + sentence.length() > chunkSize && current.length() > 0) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            if (sentence.length() > chunkSize) {
                for (int i = 0; i < sentence.length(); i += chunkSize) {
                    pieces.add(sentence.substring(i, Math.min(sentence.length(), i + chunkSize)));
                }
            } else {
                current.append(sentence);
            }
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }
}
