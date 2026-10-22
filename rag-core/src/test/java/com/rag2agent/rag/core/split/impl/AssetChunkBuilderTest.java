package com.rag2agent.rag.core.split.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rag2agent.rag.core.document.StructuredDocument;
import com.rag2agent.rag.core.document.StructuredDocument.Asset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssetChunkBuilderTest {

    @Test
    void indexesVisionTextWithPageAndAssetMetadata() {
        StructuredDocument document = new StructuredDocument(
                "doc-1", "", "", List.of(), List.of(), List.of(), List.of(
                new Asset("pictures-0", "picture", "趋势图", null, null, null,
                        "收入逐季增长", 3, null)),
                Map.of());

        var chunks = new AssetChunkBuilder().build(document, 4);

        assertEquals(1, chunks.size());
        assertTrue(chunks.getFirst().content().contains("收入逐季增长"));
        assertEquals("pictures-0", chunks.getFirst().metadata().get("assetId"));
        assertEquals("image_asset", chunks.getFirst().metadata().get("contentType"));
        assertEquals(4, chunks.getFirst().position());
    }
}
