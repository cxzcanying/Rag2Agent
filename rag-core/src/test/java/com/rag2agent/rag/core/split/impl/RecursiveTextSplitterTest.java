package com.rag2agent.rag.core.split.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rag2agent.rag.core.document.ParsedDocument;
import com.rag2agent.rag.core.split.TextChunk;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecursiveTextSplitterTest {

    @Test
    void split_shortTextStaysOneChunk() {
        RecursiveTextSplitter splitter = new RecursiveTextSplitter();
        List<TextChunk> chunks = splitter.split(document("一段短文本。"));

        assertEquals(1, chunks.size());
        assertEquals("doc-1-0", chunks.get(0).id());
    }

    @Test
    void split_longTextRespectsChunkSize() {
        RecursiveTextSplitter splitter = new RecursiveTextSplitter(100, 10);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            longText.append("这是用于切块测试的句子，包含一定长度的中文内容。");
        }
        List<TextChunk> chunks = splitter.split(document(longText.toString()));

        assertTrue(chunks.size() >= 2, "长文本应切出多个 chunk");
        for (TextChunk chunk : chunks) {
            assertTrue(chunk.content().length() <= 110, "chunk 长度不应超过 chunkSize+overlap");
            assertEquals("doc-1", chunk.documentId());
        }
    }

    @Test
    void split_applyOverlapBetweenChunks() {
        RecursiveTextSplitter splitter = new RecursiveTextSplitter(60, 15);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            longText.append("第一段内容，说明切块效果。第二句补充细节。");
        }
        List<TextChunk> chunks = splitter.split(document(longText.toString()));

        assertTrue(chunks.size() >= 2);
        String firstTail = chunks.get(0).content();
        String tail = firstTail.substring(Math.max(0, firstTail.length() - 15));
        assertTrue(chunks.get(1).content().startsWith(tail), "第二块应以第一块尾部开头（overlap）");
    }

    @Test
    void split_skipsBlankParagraphs() {
        RecursiveTextSplitter splitter = new RecursiveTextSplitter();
        List<TextChunk> chunks = splitter.split(document("第一段。\n\n\n\n第二段。"));

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).content().contains("第一段"));
        assertTrue(chunks.get(1).content().contains("第二段"));
    }

    private ParsedDocument document(String text) {
        return new ParsedDocument("doc-1", text, List.of(text), Map.of());
    }
}
