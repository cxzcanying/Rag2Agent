package com.rag2agent.rag.core.split.impl;

import com.rag2agent.rag.core.document.StructuredDocument;
import com.rag2agent.rag.core.document.StructuredDocument.Asset;
import com.rag2agent.rag.core.split.TextChunk;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将图片 OCR/视觉描述转为可检索 chunk。 */
public final class AssetChunkBuilder {

    public List<TextChunk> build(StructuredDocument document, int startPosition) {
        List<TextChunk> chunks = new ArrayList<>();
        int position = startPosition;
        for (Asset asset : document.assets()) {
            String searchable = asset.visionText();
            if (searchable == null || searchable.isBlank()) {
                searchable = asset.title();
            }
            if (searchable == null || searchable.isBlank()) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("contentType", "image_asset");
            metadata.put("assetId", asset.id());
            if (asset.page() != null) {
                metadata.put("pageNumber", asset.page());
            }
            String content = "图片标题：" + fallback(asset.title(), asset.id())
                    + "\n图片语义：" + searchable
                    + "\n页码：" + (asset.page() == null ? "未知" : asset.page());
            chunks.add(new TextChunk(
                    document.documentId() + "-asset-" + asset.id(),
                    document.documentId(),
                    content,
                    position++,
                    metadata));
        }
        return chunks;
    }

    private String fallback(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
