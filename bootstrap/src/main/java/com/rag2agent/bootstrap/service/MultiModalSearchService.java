package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.dto.SearchDtos.MultimodalSearchResponse;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import com.rag2agent.infra.ai.client.DocumentIntelligenceClient;
import com.rag2agent.infra.ai.client.VisionClient;
import com.rag2agent.infra.ai.config.DoclingProperties;
import com.rag2agent.infra.ai.model.DocumentIntelligenceResult;
import com.rag2agent.infra.ai.model.VisionRequest;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MultiModalSearchService {

    private static final Logger log = LoggerFactory.getLogger(MultiModalSearchService.class);

    private final KnowledgeBaseService knowledgeBaseService;
    private final HybridSearchService searchService;
    private final VisionClient visionClient;
    private final DocumentIntelligenceClient documentClient;
    private final DoclingProperties doclingProperties;
    private TokenCostService tokenCostService;

    public MultiModalSearchService(
            KnowledgeBaseService knowledgeBaseService,
            HybridSearchService searchService,
            VisionClient visionClient,
            DocumentIntelligenceClient documentClient,
            DoclingProperties doclingProperties) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.searchService = searchService;
        this.visionClient = visionClient;
        this.documentClient = documentClient;
        this.doclingProperties = doclingProperties;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTokenCostService(TokenCostService tokenCostService) {
        this.tokenCostService = tokenCostService;
    }

    public MultimodalSearchResponse search(
            Long userId,
            Long kbId,
            String query,
            byte[] image,
            String imageContentType,
            int topK) {
        knowledgeBaseService.requireReadable(userId, kbId);
        if (topK < 1 || topK > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "topK 必须在 1~100 之间");
        }
        boolean hasImage = image != null && image.length > 0;
        if (!hasImage && (query == null || query.isBlank())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "query 和 image 至少提供一个");
        }
        if (hasImage && image.length > 10 * 1024 * 1024) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "图片不能超过 10MB");
        }
        String effectiveQuery = query == null ? "" : query.trim();
        if (hasImage) {
            String description = describeImage(userId, new VisionRequest(
                    """
                    将图片转成用于企业知识库检索的简洁描述。
                    提取图中文字、实体、指标、图表趋势和与用户问题相关的对象。
                    只输出检索描述，不回答问题。
                    用户问题：%s
                    """.formatted(effectiveQuery),
                    image,
                    imageContentType));
            effectiveQuery = effectiveQuery.isBlank()
                    ? description
                    : effectiveQuery + "\n" + description;
        }
        List<RetrievalResult> results = searchService.search(kbId, effectiveQuery, topK);
        return new MultimodalSearchResponse(effectiveQuery, hasImage, results);
    }

    private String describeImage(Long userId, VisionRequest request) {
        RuntimeException visionFailure = null;
        if (visionClient.available()) {
            java.time.Instant startedAt = java.time.Instant.now();
            var context = new TokenCostService.CostContext(
                    java.util.UUID.randomUUID().toString(), null, null, userId,
                    com.rag2agent.bootstrap.tenancy.TenantContext.getTenantId(),
                    "vision.multimodal_search", startedAt);
            try {
                var result = visionClient.describe(request);
                if (tokenCostService != null) {
                    tokenCostService.recordVision(context, request.prompt(), result);
                }
                return result.content();
            } catch (RuntimeException exception) {
                if (tokenCostService != null) {
                    tokenCostService.recordFailure(
                            context, visionClient.providerName(),
                            visionClient.modelName(), "FAILED");
                }
                visionFailure = exception;
                log.warn("Vision 描述失败，尝试 Docling OCR 降级: reason={}",
                        exception.getMessage());
            }
        }
        if (!doclingProperties.isEnabled()) {
            throw new BusinessException(
                    ErrorCode.UPSTREAM_UNAVAILABLE, "Vision 与 Docling OCR 均不可用");
        }
        Path file = null;
        try {
            String suffix = extension(request.contentType());
            file = Files.createTempFile("rag2agent-multimodal-", suffix);
            Files.write(file, request.image());
            DocumentIntelligenceResult result = documentClient.convert(
                    file, "query-image" + suffix, request.contentType());
            String text = result.markdown() == null || result.markdown().isBlank()
                    ? result.text()
                    : result.markdown();
            if (text == null || text.isBlank()) {
                throw new BusinessException(
                        ErrorCode.UPSTREAM_UNAVAILABLE, "Docling OCR 未提取到图片文本");
            }
            return text.trim();
        } catch (RuntimeException exception) {
            if (visionFailure != null) {
                exception.addSuppressed(visionFailure);
            }
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "图片临时文件处理失败");
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception ignored) {
                    // 临时文件清理失败不影响检索结果。
                }
            }
        }
    }

    private String extension(String contentType) {
        return switch (contentType == null ? "" : contentType.toLowerCase()) {
            case "image/jpeg" -> ".jpg";
            case "image/tiff" -> ".tiff";
            default -> ".png";
        };
    }
}
