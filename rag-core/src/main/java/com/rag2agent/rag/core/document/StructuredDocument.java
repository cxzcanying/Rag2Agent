package com.rag2agent.rag.core.document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多模态文档的统一解析结果。
 *
 * <p>当前仍转换为 ParsedDocument 供既有切块链路消费，同时保留元素、表格和资产结构，
 * 为后续表头传播、区域引用和图谱抽取提供稳定数据源。
 */
public record StructuredDocument(
        String documentId,
        String text,
        String markdown,
        List<String> pages,
        List<Element> elements,
        List<Table> tables,
        List<Asset> assets,
        Map<String, Object> metadata) {

    public StructuredDocument {
        pages = List.copyOf(pages);
        elements = List.copyOf(elements);
        tables = List.copyOf(tables);
        assets = List.copyOf(assets);
        metadata = Map.copyOf(metadata);
    }

    public ParsedDocument toParsedDocument() {
        return new ParsedDocument(documentId, text, pages, metadata);
    }

    public static StructuredDocument fromParsed(ParsedDocument document, String parser) {
        List<Element> elements = new ArrayList<>();
        for (int index = 0; index < document.pages().size(); index++) {
            String pageText = document.pages().get(index);
            elements.add(new Element(
                    "page-" + (index + 1),
                    null,
                    "page_text",
                    index,
                    index + 1,
                    index + 1,
                    null,
                    pageText,
                    Map.of()));
        }
        Map<String, Object> metadata = new LinkedHashMap<>(document.metadata());
        metadata.put("parser", parser);
        metadata.put("elementCount", elements.size());
        metadata.put("tableCount", 0);
        metadata.put("assetCount", 0);
        return new StructuredDocument(
                document.documentId(),
                document.text(),
                document.text(),
                document.pages(),
                elements,
                List.of(),
                List.of(),
                metadata);
    }

    public record BoundingBox(double left, double top, double right, double bottom, String coordinateOrigin) {}

    public record Element(
            String id,
            String parentId,
            String type,
            int ordinal,
            Integer pageFrom,
            Integer pageTo,
            BoundingBox boundingBox,
            String text,
            Map<String, Object> metadata) {

        public Element {
            metadata = Map.copyOf(metadata);
        }
    }

    public record Table(
            String id,
            String title,
            List<String> headers,
            List<Row> rows,
            Integer pageFrom,
            Integer pageTo,
            BoundingBox boundingBox,
            String markdown) {

        public Table {
            headers = List.copyOf(headers);
            rows = List.copyOf(rows);
        }
    }

    public record Row(
            String id,
            int ordinal,
            List<String> cells,
            String semanticText,
            Integer page,
            BoundingBox boundingBox) {

        public Row {
            cells = List.copyOf(cells);
        }
    }

    public record Asset(
            String id,
            String type,
            String title,
            String contentReference,
            byte[] binary,
            String contentType,
            String visionText,
            Integer page,
            BoundingBox boundingBox) {

        public Asset {
            binary = binary == null ? null : binary.clone();
        }

        public Asset(
                String id,
                String type,
                String title,
                String contentReference,
                Integer page,
                BoundingBox boundingBox) {
            this(id, type, title, contentReference, null, null, page, boundingBox);
        }

        public Asset(
                String id,
                String type,
                String title,
                String contentReference,
                byte[] binary,
                String contentType,
                Integer page,
                BoundingBox boundingBox) {
            this(id, type, title, contentReference, binary, contentType, null, page, boundingBox);
        }
    }
}
