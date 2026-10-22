package com.rag2agent.bootstrap.service;

import com.rag2agent.infra.ai.client.VisionClient;
import com.rag2agent.infra.ai.model.VisionRequest;
import com.rag2agent.infra.ai.model.VisionResult;
import com.rag2agent.rag.core.document.StructuredDocument;
import com.rag2agent.rag.core.document.StructuredDocument.Asset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class VisionEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(VisionEnrichmentService.class);
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private final VisionClient visionClient;

    public VisionEnrichmentService(VisionClient visionClient) {
        this.visionClient = visionClient;
    }

    public StructuredDocument enrich(StructuredDocument document) {
        if (!visionClient.available() || document.assets().isEmpty()) {
            return document;
        }
        List<Asset> enriched = new ArrayList<>();
        for (Asset asset : document.assets()) {
            if (asset.binary() == null
                    || asset.binary().length == 0
                    || asset.binary().length > MAX_IMAGE_BYTES) {
                enriched.add(asset);
                continue;
            }
            try {
                VisionResult result = visionClient.describe(new VisionRequest(
                        """
                        描述这张企业文档图片中可检索的信息：文字、图表标题、坐标、趋势、关键数值和结构。
                        不推断无法从图片确认的事实。只输出简洁事实描述。
                        """,
                        asset.binary(),
                        asset.contentType()));
                enriched.add(new Asset(
                        asset.id(),
                        asset.type(),
                        asset.title(),
                        asset.contentReference(),
                        asset.binary(),
                        asset.contentType(),
                        result.content(),
                        asset.page(),
                        asset.boundingBox()));
            } catch (RuntimeException exception) {
                log.warn("图片语义生成失败，保留原始资产: assetId={}, reason={}",
                        asset.id(), exception.getMessage());
                enriched.add(asset);
            }
        }
        return new StructuredDocument(
                document.documentId(),
                document.text(),
                document.markdown(),
                document.pages(),
                document.elements(),
                document.tables(),
                enriched,
                document.metadata());
    }
}
