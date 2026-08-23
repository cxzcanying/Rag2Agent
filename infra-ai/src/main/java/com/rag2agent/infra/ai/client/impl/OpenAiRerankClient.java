package com.rag2agent.infra.ai.client.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag2agent.infra.ai.client.RerankClient;
import com.rag2agent.infra.ai.config.AiProviderProperties.Provider;
import com.rag2agent.infra.ai.exception.AiClientException;
import com.rag2agent.infra.ai.model.RerankRequest;
import com.rag2agent.infra.ai.model.RerankResponse;
import com.rag2agent.infra.ai.model.RerankResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * OpenAI 兼容 Rerank 客户端（硅基流动 bge-reranker-v2-m3）。
 * @author 21311
 */
public class OpenAiRerankClient implements RerankClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;

    public OpenAiRerankClient(Provider provider) {
        this.baseUrl = trimTrailingSlash(provider.getBaseUrl());
        this.apiKey = provider.getApiKey();
        this.defaultModel = provider.getRerankModel();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public RerankResponse rerank(RerankRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", request.model() == null || request.model().isBlank() ? defaultModel : request.model());
        //用户的问题
        root.put("query", request.query());
        //候选文档列表（从向量检索捞回来的那批），每个填进数组
        ArrayNode documents = root.putArray("documents");
        request.documents().forEach(documents::add);
        //topK 为 0 表示不限制
        if (request.topK() > 0) {
            root.put("top_n", request.topK());
        }

        try (Response response = post(root.toString())) {
            JsonNode json = mapper.readTree(response.body().string());
            List<RerankResult> results = new ArrayList<>();
            //遍历每个结果
            for (JsonNode item : json.path("results")) {
                //把"这个结果在原始 documents 里的下标"和"相关度分数"装进 RerankResult 对象，注意是位置而不是排序名次
                results.add(new RerankResult(item.path("index").asInt(), item.path("relevance_score").asDouble()));
            }
            return new RerankResponse(results);
        } catch (IOException e) {
            throw new AiClientException("Rerank 调用失败: " + e.getMessage(), e);
        }
    }

    private Response post(String jsonBody) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + "/rerank")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON))
                .build();
        return AiHttpExecutor.execute(http, request, "Rerank");
    }

    /**
    把 URL 末尾的所有斜杠去掉
     空值防御：url 为 null 时返回空字符串，避免 NPE
     */
    private static String trimTrailingSlash(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }
}
