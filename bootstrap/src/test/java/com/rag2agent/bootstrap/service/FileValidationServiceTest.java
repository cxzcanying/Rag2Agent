package com.rag2agent.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class FileValidationServiceTest {

    private final FileValidationService service = new FileValidationService();

    @Test
    void acceptsOfficeZipWithMatchingPayload() throws Exception {
        byte[] bytes = zip(Map.of(
                "[Content_Types].xml", "<Types/>",
                "xl/workbook.xml", "<workbook/>"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "data.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);

        assertDoesNotThrow(() -> service.validate(file, "xlsx"));
    }

    @Test
    void rejectsGenericZipDisguisedAsOfficeDocument() throws Exception {
        byte[] bytes = zip(Map.of("readme.txt", "not an office document"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes);

        assertThrows(IllegalArgumentException.class, () -> service.validate(file, "docx"));
    }

    private byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
