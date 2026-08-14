package com.rag2agent.rag.core.document.impl;

import com.rag2agent.rag.core.document.DocumentParser;
import com.rag2agent.rag.core.document.DocumentSource;
import com.rag2agent.rag.core.document.ParsedDocument;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * PDFBox 文本解析器：逐页提取文本层。
 * 注意：仅处理文本型 PDF；扫描件/图片 PDF 需要 OCR（后续增强）。
 * 表格内容会以文本行形式保留，专门的表格结构化提取后续再做。
 *
 * @author 21311
 */
public class PdfBoxDocumentParser implements DocumentParser {

    @Override
    public ParsedDocument parse(DocumentSource source) {
        List<String> pages = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        try (InputStream input = source.uri().toURL().openStream()) {
            byte[] bytes = input.readAllBytes();
            try (PDDocument document = Loader.loadPDF(bytes)) {
                PDFTextStripper stripper = new PDFTextStripper();
                //注意页码对齐实际页码都是从1开始的
                for (int pageIndex = 1; pageIndex <= document.getNumberOfPages(); pageIndex++) {
                    //把文档按页切开
                    stripper.setStartPage(pageIndex);
                    stripper.setEndPage(pageIndex);
                    String pageText = stripper.getText(document);
                    //作为溯源依据存储页码
                    pages.add(pageText);
                    fullText.append(pageText).append("\n\n");
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("PDF 解析失败: " + source.fileName(), e);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("pageCount", pages.size());
        metadata.putAll(source.metadata() == null ? Map.of() : source.metadata());
        return new ParsedDocument(source.documentId(), fullText.toString().trim(), pages, metadata);
    }
}
