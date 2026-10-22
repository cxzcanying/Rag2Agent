package com.rag2agent.infra.ai.client.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rag2agent.infra.ai.config.AiResilienceProperties;
import com.rag2agent.infra.ai.config.DoclingProperties;
import com.rag2agent.infra.ai.model.DocumentIntelligenceResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DoclingClientTest {

    private MockWebServer server;
    private DoclingClient client;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        DoclingProperties properties = new DoclingProperties();
        properties.setBaseUrl(server.url("/").toString());
        properties.setOcrLanguages(List.of("ch"));
        client = new DoclingClient(
                properties, new AiResilienceProperties(), new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void sendsMultipartOptionsAndParsesDocument() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "status": "success",
                          "processing_time": 1.25,
                          "document": {
                            "json_content": {
                              "schema_name": "DoclingDocument",
                              "version": "1.0",
                              "body": {"children": []},
                              "texts": [],
                              "tables": [],
                              "pictures": [],
                              "pages": {}
                            },
                            "md_content": "转换结果",
                            "text_content": "转换结果"
                          }
                        }
                        """));
        Path file = Files.writeString(tempDir.resolve("sample.docx"), "not-a-real-docx");

        DocumentIntelligenceResult result = client.convert(
                file, "sample.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        assertEquals("success", result.status());
        assertEquals("DoclingDocument", result.document().path("schema_name").asText());
        assertEquals("转换结果", result.markdown());
        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertEquals("/v1/convert/file", request.getPath());
        assertTrue(body.contains("name=\"to_formats\""));
        assertTrue(body.contains("name=\"ocr_lang\""));
        assertTrue(body.contains("ch"));
        assertTrue(body.contains("rapidocr"));
        assertTrue(body.contains("accurate"));
    }
}
