package com.rag2agent.infra.ai.client.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.infra.ai.client.DocumentIntelligenceClient;
import com.rag2agent.infra.ai.config.AiResilienceProperties;
import com.rag2agent.infra.ai.config.DoclingProperties;
import com.rag2agent.infra.ai.exception.AiClientException;
import com.rag2agent.infra.ai.model.DocumentIntelligenceResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Docling Serve 文件转换客户端。 */
public class DoclingClient implements DocumentIntelligenceClient {

    private static final MediaType OCTET_STREAM = MediaType.get("application/octet-stream");

    private final ObjectMapper mapper = new ObjectMapper();
    private final DoclingProperties properties;
    private final OkHttpClient http;
    private final AiHttpExecutor executor;

    public DoclingClient(
            DoclingProperties properties,
            AiResilienceProperties resilience,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.executor = new AiHttpExecutor(resilience, meterRegistry, "docling", "docling-serve");
        this.http = new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(properties.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(properties.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    @Override
    public DocumentIntelligenceResult convert(Path file, String fileName, String contentType) {
        MediaType fileType = contentType == null || contentType.isBlank()
                ? OCTET_STREAM
                : MediaType.parse(contentType);
        MultipartBody.Builder form = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("files", fileName, okhttp3.RequestBody.create(file.toFile(), fileType))
                .addFormDataPart("to_formats", "json")
                .addFormDataPart("to_formats", "md")
                .addFormDataPart("do_ocr", "true")
                .addFormDataPart("force_ocr", "false")
                .addFormDataPart("ocr_preset", properties.getOcrPreset())
                .addFormDataPart("table_mode", properties.getTableMode())
                .addFormDataPart("do_table_structure", "true")
                .addFormDataPart("include_page_images", "false")
                .addFormDataPart("image_export_mode", "placeholder");
        for (String language : properties.getOcrLanguages()) {
            form.addFormDataPart("ocr_lang", language);
        }

        Request request = new Request.Builder()
                .url(trimTrailingSlash(properties.getBaseUrl()) + normalizeEndpoint(properties.getEndpoint()))
                .post(form.build())
                .build();
        try (Response response = executor.execute(http, request, "document.convert")) {
            JsonNode payload = mapper.readTree(response.body().string());
            JsonNode document = payload.path("document");
            JsonNode jsonContent = document.path("json_content");
            if (jsonContent.isMissingNode() || jsonContent.isNull() || !jsonContent.isObject()) {
                throw new AiClientException("Docling 未返回 json_content");
            }
            return new DocumentIntelligenceResult(
                    payload.path("status").asText("success"),
                    payload.path("processing_time").asDouble(0),
                    jsonContent,
                    document.path("md_content").asText(""),
                    document.path("text_content").asText(""));
        } catch (IOException exception) {
            throw new AiClientException("Docling 调用失败: " + exception.getMessage(), exception);
        }
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "/v1/convert/file";
        }
        return endpoint.startsWith("/") ? endpoint : "/" + endpoint;
    }

    private static String trimTrailingSlash(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }
}
