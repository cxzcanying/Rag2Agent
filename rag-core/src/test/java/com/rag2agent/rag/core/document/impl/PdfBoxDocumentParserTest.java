package com.rag2agent.rag.core.document.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rag2agent.rag.core.document.DocumentSource;
import com.rag2agent.rag.core.document.ParsedDocument;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfBoxDocumentParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parse_extractsTextPerPage() throws Exception {
        File pdf = createTestPdf();

        ParsedDocument parsed = new PdfBoxDocumentParser().parse(new DocumentSource(
                "doc-1",
                "test.pdf",
                pdf.toURI(),
                "application/pdf",
                Map.of("kb", "kb-1")));

        assertEquals(1, parsed.pages().size());
        assertTrue(parsed.text().contains("RAG2Agent PDF parser test"));
        assertTrue(parsed.text().contains("Second line"));
        assertEquals("kb-1", parsed.metadata().get("kb"));
        assertEquals(1, parsed.metadata().get("pageCount"));
    }

    private File createTestPdf() throws Exception {
        File file = Files.createTempFile(tempDir, "test", ".pdf").toFile();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("RAG2Agent PDF parser test");
                stream.newLineAtOffset(0, -20);
                stream.showText("Second line with content.");
                stream.endText();
            }
            document.save(file);
        }
        return file;
    }
}
