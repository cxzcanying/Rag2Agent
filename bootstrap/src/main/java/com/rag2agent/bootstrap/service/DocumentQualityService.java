package com.rag2agent.bootstrap.service;

import com.rag2agent.rag.core.document.ParsedDocument;
import com.rag2agent.rag.core.document.StructuredDocument;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 解析质量门禁：低质量内容进入 NEEDS_REVIEW，不生成看似成功的索引。 */
public class DocumentQualityService {

    public QualityReport check(StructuredDocument structured, ParsedDocument parsed) {
        String text = parsed.text() == null ? "" : parsed.text();
        if (text.trim().length() < 50) {
            return QualityReport.review("EMPTY_OR_TOO_SHORT", Map.of("chars", text.length()));
        }
        long controls = text.codePoints()
                .filter(cp -> Character.isISOControl(cp) && !Character.isWhitespace(cp))
                .count();
        if (controls > Math.max(20, text.length() * 0.01)) {
            return QualityReport.review("TOO_MANY_CONTROL_CHARACTERS", Map.of("controls", controls));
        }
        List<String> lines = text.lines().map(String::trim).filter(line -> line.length() > 20).toList();
        if (lines.size() > 20) {
            Set<String> unique = new HashSet<>(lines);
            if (unique.size() * 100 / lines.size() < 20) {
                return QualityReport.review("EXCESSIVE_DUPLICATE_TEXT",
                        Map.of("lines", lines.size(), "unique", unique.size()));
            }
        }
        if (parsed.pages() != null && parsed.pages().size() > 1000) {
            return QualityReport.review("EXCESSIVE_PAGE_COUNT", Map.of("pages", parsed.pages().size()));
        }
        Object confidence = structured.metadata().get("ocrConfidence");
        if (confidence instanceof Number number && number.doubleValue() < 0.5) {
            return QualityReport.review("LOW_OCR_CONFIDENCE", Map.of("confidence", number));
        }
        return new QualityReport(true, null, Map.of(
                "chars", text.length(),
                "pages", parsed.pages() == null ? 0 : parsed.pages().size(),
                "tables", structured.tables().size(),
                "assets", structured.assets().size()));
    }

    public record QualityReport(
            boolean accepted,
            String reason,
            Map<String, Object> details) {

        static QualityReport review(String reason, Map<String, Object> details) {
            return new QualityReport(false, reason, details);
        }
    }
}
