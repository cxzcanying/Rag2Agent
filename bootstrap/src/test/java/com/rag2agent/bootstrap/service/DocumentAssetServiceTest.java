package com.rag2agent.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.bootstrap.service.DocumentStructureRepository.AssetRecord;
import com.rag2agent.bootstrap.storage.MinioStorageService;
import com.rag2agent.infra.ai.client.VisionClient;
import com.rag2agent.infra.ai.model.VisionRequest;
import com.rag2agent.infra.ai.model.VisionResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DocumentAssetServiceTest {

    @Test
    void presignsOwnedAssetWithPageMetadata() throws Exception {
        DocumentService documents = mock(DocumentService.class);
        DocumentStructureRepository repository = mock(DocumentStructureRepository.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        DocumentMeta document = new DocumentMeta();
        document.setId(7L);
        document.setVersion(2);
        when(documents.requireOwnedDocument(3L, 7L)).thenReturn(document);
        when(repository.findAsset(7L, 2, "pictures-0"))
                .thenReturn(Optional.of(new AssetRecord(
                        "pictures-0", "picture", "趋势图", "assets/7/2/pictures-0",
                        "image/png", null, 3, null)));
        when(storage.presignGet("assets/7/2/pictures-0", 600))
                .thenReturn("http://minio/asset");

        VisionClient vision = mock(VisionClient.class);
        var result = new DocumentAssetService(documents, repository, storage, vision)
                .presign(3L, 7L, "pictures-0", null);

        assertEquals("http://minio/asset", result.url());
        assertEquals(3, result.pageNo());
    }

    @Test
    void describesOwnedAssetThroughVisionClient() throws Exception {
        DocumentService documents = mock(DocumentService.class);
        DocumentStructureRepository repository = mock(DocumentStructureRepository.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        VisionClient vision = mock(VisionClient.class);
        DocumentMeta document = new DocumentMeta();
        document.setId(7L);
        document.setVersion(2);
        when(documents.requireOwnedDocument(3L, 7L)).thenReturn(document);
        when(repository.findAsset(7L, 2, "pictures-0"))
                .thenReturn(Optional.of(new AssetRecord(
                        "pictures-0", "picture", "趋势图", "assets/7/2/pictures-0",
                        "image/png", "趋势向上", 3, null)));
        when(vision.available()).thenReturn(true);
        when(storage.download("assets/7/2/pictures-0")).thenReturn(new byte[] {1});
        when(vision.describe(org.mockito.ArgumentMatchers.any(VisionRequest.class)))
                .thenReturn(new VisionResult("趋势向上", "qwen-vl", "qwen3-vl-flash"));

        var result = new DocumentAssetService(documents, repository, storage, vision)
                .describe(3L, 7L, "pictures-0", null, "趋势如何");

        assertEquals("趋势向上", result.answer());
        assertEquals("qwen3-vl-flash", result.model());
    }
}
