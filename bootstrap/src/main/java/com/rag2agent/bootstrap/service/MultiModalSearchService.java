package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.dto.SearchDtos.MultimodalSearchResponse;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import com.rag2agent.infra.ai.client.VisionClient;
import com.rag2agent.infra.ai.model.VisionRequest;
import com.rag2agent.infra.ai.model.VisionResult;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MultiModalSearchService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final HybridSearchService searchService;
    private final VisionClient visionClient;

    public MultiModalSearchService(
            KnowledgeBaseService knowledgeBaseService,
            HybridSearchService searchService,
            VisionClient visionClient) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.searchService = searchService;
        this.visionClient = visionClient;
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
            if (!visionClient.available()) {
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "Vision provider 未启用");
            }
            VisionResult description = visionClient.describe(new VisionRequest(
                    """
                    将图片转成用于企业知识库检索的简洁描述。
                    提取图中文字、实体、指标、图表趋势和与用户问题相关的对象。
                    只输出检索描述，不回答问题。
                    用户问题：%s
                    """.formatted(effectiveQuery),
                    image,
                    imageContentType));
            effectiveQuery = effectiveQuery.isBlank()
                    ? description.content()
                    : effectiveQuery + "\n" + description.content();
        }
        List<RetrievalResult> results = searchService.search(kbId, effectiveQuery, topK);
        return new MultimodalSearchResponse(effectiveQuery, hasImage, results);
    }
}
