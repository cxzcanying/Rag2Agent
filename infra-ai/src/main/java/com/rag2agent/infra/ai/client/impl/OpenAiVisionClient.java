package com.rag2agent.infra.ai.client.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag2agent.infra.ai.client.VisionClient;
import com.rag2agent.infra.ai.config.AiProviderProperties.Provider;
import com.rag2agent.infra.ai.config.AiResilienceProperties;
import com.rag2agent.infra.ai.exception.AiClientException;
import com.rag2agent.infra.ai.model.VisionRequest;
import com.rag2agent.infra.ai.model.VisionResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class OpenAiVisionClient implements VisionClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http;
    private final AiHttpExecutor executor;
    private final String baseUrl;
    private final String apiKey;
    private final String providerName;
    private final String model;

    public OpenAiVisionClient(
            Provider provider,
            AiResilienceProperties resilience,
            MeterRegistry meterRegistry) {
        this.baseUrl = provider.getBaseUrl().replaceAll("/+$", "");
        this.apiKey = provider.getApiKey();
        this.providerName = provider.getName();
        this.model = provider.getVisionModel();
        this.executor = new AiHttpExecutor(resilience, meterRegistry, providerName, model);
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public VisionResult describe(VisionRequest request) {
        if (request.image().length == 0) {
            throw new IllegalArgumentException("图片内容不能为空");
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", 1200);
        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        content.addObject().put("type", "text").put("text", request.prompt());
        ObjectNode image = content.addObject();
        image.put("type", "image_url");
        String contentType = request.contentType() == null || request.contentType().isBlank()
                ? "image/png"
                : request.contentType();
        image.putObject("image_url").put(
                "url", "data:" + contentType + ";base64,"
                        + Base64.getEncoder().encodeToString(request.image()));
        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(root.toString(), JSON))
                .build();
        try (Response response = executor.execute(http, httpRequest, "vision.describe")) {
            JsonNode json = mapper.readTree(response.body().string());
            String text = json.path("choices").path(0).path("message").path("content").asText("");
            if (text.isBlank()) {
                throw new AiClientException("Vision 返回内容为空");
            }
            return new VisionResult(text, providerName, model);
        } catch (IOException exception) {
            throw new AiClientException("Vision 调用失败: " + exception.getMessage(), exception);
        }
    }
}
