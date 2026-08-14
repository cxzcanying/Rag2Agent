package com.rag2agent.infra.ai.client.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag2agent.infra.ai.client.EmbeddingClient;
import com.rag2agent.infra.ai.config.AiProviderProperties.Provider;
import com.rag2agent.infra.ai.exception.AiClientException;
import com.rag2agent.infra.ai.model.EmbeddingRequest;
import com.rag2agent.infra.ai.model.EmbeddingResponse;
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
 * OpenAI 兼容 Embedding 客户端（硅基流动 BGE-M3 等）。
 * 职责：把文本变成 1024 维向量，向量写入 pgvector 后供语义检索使用。
 * 请求走 OpenAI 兼容协议：POST /embeddings，body 里 model + input 数组。
 * @author 21311
 */
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;

    public OpenAiEmbeddingClient(Provider provider) {
        this.baseUrl = trimTrailingSlash(provider.getBaseUrl());
        this.apiKey = provider.getApiKey();
        this.defaultModel = provider.getEmbeddingModel();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                //embedding 生成比 chat 快，60 秒足够，不用 90 秒
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", request.model() == null || request.model().isBlank() ? defaultModel : request.model());
        //一次可以传多个文本批量向量化，省请求数
        ArrayNode input = root.putArray("input");
        request.inputs().forEach(input::add);

        try (Response response = post(root.toString())) {
            // 响应结构：{"data": [{"embedding": [0.1, 0.2, ...1024 个], "index": 0}, ...]}
            // data 数组顺序与请求 input 数组一一对应，所以解析结果可以直接按下标映射回文本
            JsonNode json = mapper.readTree(response.body().string());
            List<List<Float>> vectors = new ArrayList<>();
            //响应 data[] 数组里每个元素的 embedding 字段是 1024 个 float，需要取出来向量化
            for (JsonNode item : json.path("data")) {
                List<Float> vector = new ArrayList<>();
                //把 embedding 数组里的每个数字取出来
                item.path("embedding").forEach(v -> vector.add((float) v.asDouble()));
                //把这个向量加入结果列表
                vectors.add(vector);
            }
            return new EmbeddingResponse(vectors);
        } catch (IOException e) {
            // 网络异常/解析失败统一转业务异常，由上层决定重试或标记任务失败
            throw new AiClientException("Embedding 调用失败: " + e.getMessage(), e);
        }
    }

    private Response post(String jsonBody) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + "/embeddings")
                // 鉴权：API Key 放在 Authorization: Bearer 头里，不经 body 传输
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON))
                .build();
        Response response = http.newCall(request).execute();
        if (!response.isSuccessful()) {
            // 非 2xx：把服务端返回的错误体读出来透传给上层，
            // 否则只看到状态码，无法定位是 key 无效、限流还是模型名错误
            String errorBody = response.body() == null ? "" : response.body().string();
            response.close();
            throw new AiClientException("Embedding API 错误 " + response.code() + ": " + errorBody);
        }
        return response;
    }

    private static String trimTrailingSlash(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }
}
