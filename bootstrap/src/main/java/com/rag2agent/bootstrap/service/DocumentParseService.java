package com.rag2agent.bootstrap.service;

import com.rag2agent.infra.ai.client.DocumentIntelligenceClient;
import com.rag2agent.infra.ai.config.DoclingProperties;
import com.rag2agent.infra.ai.model.DocumentIntelligenceResult;
import com.rag2agent.rag.core.document.DocumentSource;
import com.rag2agent.rag.core.document.ParsedDocument;
import com.rag2agent.rag.core.document.StructuredDocument;
import com.rag2agent.rag.core.document.impl.DoclingDocumentMapper;
import com.rag2agent.rag.core.document.impl.PdfBoxDocumentParser;
import com.rag2agent.rag.core.document.impl.ExcelDocumentParser;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 文档解析入口：Docling 为主，文本型 PDF 可降级到 PDFBox。 */
@Service
public class DocumentParseService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseService.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "pdf", "docx", "pptx", "xlsx", "html", "htm",
            "png", "jpg", "jpeg", "tif", "tiff", "eml", "msg");
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("tif", "image/tiff"),
            Map.entry("tiff", "image/tiff"),
            Map.entry("eml", "message/rfc822"),
            Map.entry("msg", "application/vnd.ms-outlook"));

    private final DocumentIntelligenceClient client;
    private final DoclingProperties properties;
    private final DoclingDocumentMapper mapper = new DoclingDocumentMapper();

    public DocumentParseService(DocumentIntelligenceClient client, DoclingProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public StructuredDocument parse(
            String documentId, String fileName, String fileType, Path file) {
        String extension = normalizeExtension(fileType);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("不支持的文档类型: " + fileType);
        }
        if ("xlsx".equals(extension)) {
            return new ExcelDocumentParser().parse(documentId, fileName, file);
        }
        if ("pdf".equals(extension)) {
            try {
                ParsedDocument parsed = new PdfBoxDocumentParser().parse(new DocumentSource(
                        documentId, fileName, file.toUri(), "application/pdf", Map.of()));
                if (usableText(parsed.text())) {
                    return StructuredDocument.fromParsed(parsed, "pdfbox");
                }
            } catch (RuntimeException exception) {
                log.warn("PDFBox 文本提取失败，尝试 Docling: file={}, reason={}",
                        fileName, exception.getMessage());
            }
        }
        if (properties.isEnabled()) {
            try {
                DocumentIntelligenceResult result = client.convert(
                        file, fileName, CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"));
                return mapper.map(documentId, result.document(), result.markdown());
            } catch (RuntimeException exception) {
                if (!"pdf".equals(extension)) {
                    throw exception;
                }
                log.warn("Docling 解析失败，PDF 降级到 PDFBox: file={}, reason={}",
                        fileName, exception.getMessage());
            }
        }
        if (!"pdf".equals(extension)) {
            throw new IllegalStateException("非 PDF 文档需要启用 Docling 文档智能服务");
        }
        throw new IllegalStateException("文本型 PDF 未提取到可用内容，需要启用 Docling OCR");
    }

    public static boolean supports(String fileType) {
        return SUPPORTED_EXTENSIONS.contains(normalizeExtension(fileType));
    }

    public static String normalizeExtension(String fileType) {
        return fileType == null ? "" : fileType.toLowerCase(Locale.ROOT);
    }

    private boolean usableText(String text) {
        if (text == null || text.trim().length() < 50) {
            return false;
        }
        long printable = text.codePoints()
                .filter(cp -> Character.isLetterOrDigit(cp) || Character.isWhitespace(cp)
                        || "，。；：、（）【】《》.,;:()[]".indexOf(cp) >= 0)
                .count();
        return printable >= text.codePoints().count() * 0.7;
    }
}
