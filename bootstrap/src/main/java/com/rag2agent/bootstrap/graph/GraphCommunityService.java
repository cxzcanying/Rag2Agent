package com.rag2agent.bootstrap.graph;

import com.rag2agent.bootstrap.config.Neo4jGraphProperties;
import com.rag2agent.infra.ai.client.ChatModelClient;
import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import com.rag2agent.infra.ai.model.ChatMessage;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class GraphCommunityService {

    private final Neo4jGraphProperties properties;
    private final KnowledgeGraphRepository repository;
    private final ChatModelClient chatClient;
    private com.rag2agent.bootstrap.service.TokenCostService tokenCostService;

    public GraphCommunityService(
            Neo4jGraphProperties properties,
            KnowledgeGraphRepository repository,
            ChatModelClient chatClient) {
        this.properties = properties;
        this.repository = repository;
        this.chatClient = chatClient;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTokenCostService(com.rag2agent.bootstrap.service.TokenCostService tokenCostService) {
        this.tokenCostService = tokenCostService;
    }

    public int refresh(Long kbId) {
        requireEnabled();
        repository.rebuildCommunities(kbId);
        var communities = repository.communities(kbId, properties.getMaxResults());
        for (var community : communities) {
            java.time.Instant startedAt = java.time.Instant.now();
            ChatCompletionResponse response = chatClient.complete(new ChatCompletionRequest(
                    chatClient.providerName(),
                    null,
                    List.of(
                            new ChatMessage("system",
                                    "你是企业图谱摘要器。根据实体列表生成简洁主题摘要，不补充列表外事实。"),
                            new ChatMessage("user", """
                                    实体列表（只允许使用以下实体）：
                                    %s

                                    请生成一段不超过 200 字的主题摘要。
                                    """.formatted("- " + String.join("\n- ", community.entities())))),
                    Map.of("max_tokens", 500, "temperature", 0.1)));
            if (tokenCostService != null) {
                tokenCostService.record(
                        com.rag2agent.bootstrap.service.TokenCostService.CostContext.current(
                                "graph.community_summary", startedAt),
                        response.usage(), null, List.of(),
                        chatClient.providerName(), chatClient.modelName());
            }
            if (response != null && response.content() != null && !response.content().isBlank()) {
                repository.saveCommunitySummary(
                        kbId, community.communityId(), response.content().trim());
            }
        }
        return communities.size();
    }

    public List<KnowledgeGraphRepository.CommunitySummary> globalSummaries(Long kbId, int limit) {
        requireEnabled();
        return repository.communitySummaries(
                kbId, Math.min(Math.max(limit, 1), properties.getMaxResults()));
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("图能力未启用");
        }
    }
}
