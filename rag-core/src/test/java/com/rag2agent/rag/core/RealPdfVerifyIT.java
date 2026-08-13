package com.rag2agent.rag.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rag2agent.rag.core.document.DocumentSource;
import com.rag2agent.rag.core.document.ParsedDocument;
import com.rag2agent.rag.core.document.impl.PdfBoxDocumentParser;
import com.rag2agent.rag.core.split.TextChunk;
import com.rag2agent.rag.core.split.impl.RecursiveTextSplitter;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

/**
 * 真实 PDF 验证（手动运行，不参与 CI）：
 * 设置环境变量 VERIFY_PDF_PATHS（分号分隔的 PDF 路径）后运行：
 * mvn -pl rag-core -am test -Dtest=RealPdfVerifyIT
 * 验证中文提取、分页、切块效果，统计写入 logs/pdf-verify-report.txt。
 */
class RealPdfVerifyIT {

    @Test
    void verifyRealPdfs() throws Exception {
        String envPaths = System.getenv("VERIFY_PDF_PATHS");
        Assumptions.assumeTrue(envPaths != null && !envPaths.isBlank(),
                "未设置 VERIFY_PDF_PATHS，跳过真实 PDF 验证");
        List<Path> pdfs = Arrays.stream(envPaths.split(";"))
                .map(String::trim)
                .map(Path::of)
                .toList();

        PdfBoxDocumentParser parser = new PdfBoxDocumentParser();
        RecursiveTextSplitter splitter = new RecursiveTextSplitter();
        StringBuilder report = new StringBuilder();

        for (Path path : pdfs) {
            long sizeMb = Math.round(path.toFile().length() / 1024.0 / 1024.0);
            long start = System.currentTimeMillis();
            ParsedDocument doc = parser.parse(new DocumentSource(
                    path.getFileName().toString(), path.getFileName().toString(), path.toUri(),
                    "application/pdf", Map.of()));
            long parseMs = System.currentTimeMillis() - start;

            start = System.currentTimeMillis();
            List<TextChunk> chunks = splitter.split(doc);
            long splitMs = System.currentTimeMillis() - start;

            long chineseChars = doc.text().chars().filter(c -> c >= 0x4e00 && c <= 0x9fa5).count();
            String head = doc.text().substring(0, Math.min(120, doc.text().length())).replace('\n', ' ');

            report.append("===== ").append(path.getFileName()).append(" =====\n")
                    .append("size=").append(sizeMb).append("MB parseMs=").append(parseMs)
                    .append(" splitMs=").append(splitMs).append("\n")
                    .append("pages=").append(doc.pages().size())
                    .append(" textLen=").append(doc.text().length())
                    .append(" chineseChars=").append(chineseChars)
                    .append(" chunks=").append(chunks.size()).append("\n")
                    .append("head: ").append(head).append("\n\n");

            assertTrue(doc.pages().size() > 0, "页数应大于 0: " + path);
            assertTrue(chineseChars > 100, "应提取出中文内容: " + path);
            assertTrue(chunks.size() > 0, "应切出 chunk: " + path);
        }

        Path reportPath = Path.of("logs/pdf-verify-report.txt");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString());
    }
}
