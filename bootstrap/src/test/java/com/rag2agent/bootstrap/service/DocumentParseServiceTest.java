package com.rag2agent.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.infra.ai.client.DocumentIntelligenceClient;
import com.rag2agent.infra.ai.config.DoclingProperties;
import com.rag2agent.infra.ai.model.DocumentIntelligenceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentParseServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void parsesOfficeDocumentThroughDoclingWhenEnabled() throws Exception {
        DocumentIntelligenceClient client = mock(DocumentIntelligenceClient.class);
        DoclingProperties properties = new DoclingProperties();
        properties.setEnabled(true);
        Path file = Files.writeString(tempDir.resolve("report.docx"), "content");
        when(client.convert(file, "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .thenReturn(new DocumentIntelligenceResult(
                        "success",
                        1.0,
                        mapper.readTree("""
                                {
                                  "schema_name": "DoclingDocument",
                                  "version": "1.0",
                                  "body": {"children": [{"$ref": "#/texts/0"}]},
                                  "texts": [{"self_ref": "#/texts/0", "label": "paragraph", "text": "正文"}],
                                  "tables": [],
                                  "pictures": [],
                                  "pages": {"1": {"page_no": 1}}
                                }
                                """),
                        "正文",
                        "正文"));

        var parsed = new DocumentParseService(client, properties)
                .parse("doc-1", "report.docx", "docx", file);

        assertEquals("正文", parsed.toParsedDocument().text());
        assertEquals("docling", parsed.metadata().get("parser"));
    }

    @Test
    void rejectsNonPdfWhenDoclingIsDisabled() throws Exception {
        DoclingProperties properties = new DoclingProperties();
        Path file = Files.writeString(tempDir.resolve("report.docx"), "content");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new DocumentParseService(mock(DocumentIntelligenceClient.class), properties)
                        .parse("doc-1", "report.docx", "docx", file));

        assertEquals("非 PDF 文档需要启用 Docling 文档智能服务", exception.getMessage());
    }
}
