package com.rag2agent.infra.ai.client.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag2agent.infra.ai.client.ChatModelClient;
import com.rag2agent.infra.ai.config.AiProviderProperties.Provider;
import com.rag2agent.infra.ai.exception.AiClientException;
import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import com.rag2agent.infra.ai.model.ChatMessage;
import com.rag2agent.infra.ai.model.ToolCall;
import com.rag2agent.infra.ai.model.ToolDef;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * OpenAI 兼容 Chat 客户端（DeepSeek / Qwen / OpenAI 等）。
 * 同步返回完整内容；stream 通过 SSE 增量回调。
 * @author 21311
 */
public class OpenAiChatModelClient implements ChatModelClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;

    public OpenAiChatModelClient(Provider provider) {
        this.baseUrl = trimTrailingSlash(provider.getBaseUrl());
        this.apiKey = provider.getApiKey();
        this.defaultModel = provider.getChatModel();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                //发出请求后等下一个字节的超时
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public ChatCompletionResponse complete(ChatCompletionRequest request) {
        try (Response response = post(buildBody(request, false))) {
            JsonNode json = mapper.readTree(response.body().string());
            String content = json.path("choices").path(0).path("message").path("content").asText(null);
            String finishReason = json.path("choices").path(0).path("finish_reason").asText(null);
            Map<String, Object> usage = mapper.convertValue(
                    json.path("usage"), new TypeReference<Map<String, Object>>() {});
            List<ToolCall> toolCalls = new ArrayList<>();
            for (JsonNode tc : json.path("choices").path(0).path("message").path("tool_calls")) {
                toolCalls.add(new ToolCall(
                        tc.path("id").asText(null),
                        tc.path("type").asText("function"),
                        tc.path("function").path("name").asText(null),
                        tc.path("function").path("arguments").asText(null)));
            }
            return new ChatCompletionResponse(content, finishReason, usage, toolCalls);
        } catch (IOException e) {
            throw new AiClientException("Chat 调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void stream(ChatCompletionRequest request, Consumer<String> onDelta) {
        try (Response response = post(buildBody(request, true))) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                /*
                代码只认 data: 开头的行（line.startsWith("data:")），其他行跳过
                 */
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                /*
                  响应是一行一行文本，格式固定：data: {json} 空行 data: {json} ... 最后 data: [DONE]。
                 */
                if (data.isEmpty() || data.equals("[DONE]")) {
                    continue;
                }
                JsonNode json = mapper.readTree(data);
                //示例：{"choices":[{"delta":{"content":"你"},"finish_reason":null}]}只取content字段内容
                //使用.path()而不是.get()的原因是按路径找子节点，找不到就返回一个特殊的缺失节点，不会抛NPE异常。
                String delta = json.path("choices").path(0).path("delta").path("content").asText(null);
                if (delta != null) {
                    onDelta.accept(delta);
                }
            }
        } catch (IOException e) {
            throw new AiClientException("Chat 流式调用失败: " + e.getMessage(), e);
        }
    }

    private Response post(String jsonBody) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON))
                .build();
        Response response = http.newCall(request).execute();
        if (!response.isSuccessful()) {
            String errorBody = response.body() == null ? "" : response.body().string();
            response.close();
            throw new AiClientException("Chat API 错误 " + response.code() + ": " + errorBody);
        }
        return response;
    }

    private String buildBody(ChatCompletionRequest request, boolean stream) {
        ObjectNode root = mapper.createObjectNode();

        //优先用请求里带的，没有就用配置的默认值
        root.put("model", request.model() == null || request.model().isBlank() ? defaultModel : request.model());
        root.put("stream", stream);

        ArrayNode messages = root.putArray("messages");
        for (ChatMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            if (message.content() != null) {
                node.put("content", message.content());
            }
            if (message.name() != null) {
                node.put("name", message.name());
            }
            if (message.toolCallId() != null) {
                node.put("tool_call_id", message.toolCallId());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                ArrayNode toolCalls = node.putArray("tool_calls");
                for (ToolCall tc : message.toolCalls()) {
                    ObjectNode tcNode = toolCalls.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", tc.type() == null ? "function" : tc.type());
                    ObjectNode fn = tcNode.putObject("function");
                    fn.put("name", tc.name());
                    fn.put("arguments", tc.arguments());
                }
            }
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolDef tool : request.tools()) {
                ObjectNode t = tools.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", tool.name());
                fn.put("description", tool.description());
                fn.set("parameters", mapper.valueToTree(tool.parameters()));
            }
        }

        //请求里可以额外带 temperature（控制随机性）、max_tokens（限制输出长度）等
        if (request.options() != null) {
            request.options().forEach((key, value) -> root.set(key, mapper.valueToTree(value)));
        }
        return root.toString();
    }

    private static String trimTrailingSlash(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }
}
