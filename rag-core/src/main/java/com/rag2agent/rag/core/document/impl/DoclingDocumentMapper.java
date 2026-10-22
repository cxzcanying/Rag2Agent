package com.rag2agent.rag.core.document.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.rag2agent.rag.core.document.StructuredDocument;
import com.rag2agent.rag.core.document.StructuredDocument.Asset;
import com.rag2agent.rag.core.document.StructuredDocument.BoundingBox;
import com.rag2agent.rag.core.document.StructuredDocument.Element;
import com.rag2agent.rag.core.document.StructuredDocument.Row;
import com.rag2agent.rag.core.document.StructuredDocument.Table;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把 DoclingDocument JSON 映射为项目内部文档结构。
 *
 * <p>Docling 的内容项通过 JSON Pointer 互相引用，阅读顺序以 body.children 为准。
 * 这里保留该顺序和 provenance，避免重新按文本位置猜测顺序。
 */
public final class DoclingDocumentMapper {

    public StructuredDocument map(String documentId, JsonNode document, String markdown) {
        if (document == null || !document.isObject()) {
            throw new IllegalArgumentException("DoclingDocument 不能为空");
        }

        Map<String, JsonNode> items = new HashMap<>();
        indexItems(items, document.path("texts"), "texts");
        indexItems(items, document.path("tables"), "tables");
        indexItems(items, document.path("pictures"), "pictures");
        indexItems(items, document.path("groups"), "groups");

        List<JsonNode> readingOrder = readingOrder(document, items);
        List<Element> elements = new ArrayList<>();
        List<Table> tables = new ArrayList<>();
        List<Asset> assets = new ArrayList<>();
        Map<Integer, StringBuilder> pageText = new TreeMap<>();

        int elementIndex = 0;
        for (JsonNode item : readingOrder) {
            String reference = text(item, "self_ref", "");
            String type = text(item, "label", itemType(reference));
            JsonNode provenance = firstProvenance(item);
            Integer page = integer(provenance, "page_no");
            BoundingBox box = boundingBox(provenance.path("bbox"));
            String content;
            if (reference.startsWith("#/tables/")) {
                Table table = toTable(item, items);
                tables.add(table);
                content = table.title().isBlank()
                        ? table.markdown()
                        : table.title() + "\n" + table.markdown();
            } else if (reference.startsWith("#/pictures/")) {
                Asset asset = toAsset(item, items);
                assets.add(asset);
                content = asset.title();
            } else {
                content = text(item, "text", "");
            }
            elements.add(new Element(
                    reference.isBlank() ? "element-" + elementIndex : stableId(reference),
                    stableId(text(item.path("parent"), "$ref", null)),
                    type,
                    elementIndex++,
                    page,
                    page,
                    box,
                    content,
                    metadata(item)));
            appendPageText(pageText, page, content);
        }
        tables = mergeTableContinuations(tables);

        List<String> pages = pageText.entrySet().stream()
                .map(entry -> entry.getValue().toString().trim())
                .toList();
        String normalizedMarkdown = markdown == null ? "" : markdown.trim();
        String fullText = normalizedMarkdown.isBlank()
                ? pages.stream().filter(value -> !value.isBlank()).reduce("", (a, b) -> a + "\n\n" + b).trim()
                : normalizedMarkdown;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("parser", "docling");
        metadata.put("schemaName", text(document, "schema_name", ""));
        metadata.put("schemaVersion", text(document, "version", ""));
        metadata.put("elementCount", elements.size());
        metadata.put("tableCount", tables.size());
        metadata.put("assetCount", assets.size());
        metadata.put("pageCount", pages.size());

        return new StructuredDocument(
                documentId, fullText, normalizedMarkdown, pages, elements, tables, assets, metadata);
    }

    private static List<JsonNode> readingOrder(JsonNode document, Map<String, JsonNode> items) {
        List<JsonNode> ordered = new ArrayList<>();
        JsonNode children = document.path("body").path("children");
        appendReadingOrder(children, items, ordered);
        if (ordered.isEmpty()) {
            ordered.addAll(items.values());
            ordered.removeIf(item -> text(item, "self_ref", "").startsWith("#/groups/"));
            ordered.sort(Comparator.comparingInt(DoclingDocumentMapper::sourceIndex)
                    .thenComparing(item -> text(item, "self_ref", "")));
        }
        return ordered;
    }

    private static void appendReadingOrder(
            JsonNode children, Map<String, JsonNode> items, List<JsonNode> ordered) {
        if (!children.isArray()) {
            return;
        }
        for (JsonNode child : children) {
            String reference = text(child, "$ref", "");
            JsonNode item = items.get(reference);
            if (item == null) {
                continue;
            }
            if (reference.startsWith("#/groups/")) {
                appendReadingOrder(item.path("children"), items, ordered);
            } else {
                ordered.add(item);
            }
        }
    }

    private Table toTable(JsonNode item, Map<String, JsonNode> items) {
        List<String> captions = refs(item.path("captions"), items);
        String title = captions.isEmpty() ? "" : captions.getFirst();
        List<TableCell> cells = tableCells(item.path("data").path("table_cells"));
        int maxColumn = cells.stream().mapToInt(TableCell::column).max().orElse(-1);
        List<String> headers = new ArrayList<>();
        for (int column = 0; column <= maxColumn; column++) {
            headers.add("列" + (column + 1));
        }
        boolean hasHeaderFlags = cells.stream().anyMatch(cell -> cell.columnHeader());
        for (TableCell cell : cells) {
            if (cell.column() >= 0 && cell.column() < headers.size()
                    && (cell.columnHeader() || !hasHeaderFlags && cell.row() == 0)) {
                headers.set(cell.column(), isBlank(cell.text()) ? "列" + (cell.column() + 1) : cell.text());
            }
        }

        Map<Integer, List<TableCell>> rowsByIndex = new TreeMap<>();
        for (TableCell cell : cells) {
            if (cell.columnHeader() || !hasHeaderFlags && cell.row() == 0) {
                continue;
            }
            rowsByIndex.computeIfAbsent(cell.row(), ignored -> new ArrayList<>()).add(cell);
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Integer, List<TableCell>> entry : rowsByIndex.entrySet()) {
            List<TableCell> rowCells = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(TableCell::column))
                    .toList();
            List<String> values = rowCells.stream().map(TableCell::text).toList();
            StringBuilder semantic = new StringBuilder();
            for (int index = 0; index < values.size(); index++) {
                int column = rowCells.get(index).column();
                String header = column >= 0 && column < headers.size() ? headers.get(column) : "列" + (column + 1);
                if (!semantic.isEmpty()) {
                    semantic.append("；");
                }
                semantic.append(header).append("=").append(values.get(index));
            }
            rows.add(new Row(
                    stableId(text(item, "self_ref", "table")) + "-row-" + entry.getKey(),
                    entry.getKey(),
                    values,
                    semantic.toString(),
                    integer(firstProvenance(item), "page_no"),
                    boundingBox(firstProvenance(item).path("bbox"))));
        }

        String markdown = toMarkdown(headers, rows);
        JsonNode provenance = firstProvenance(item);
        return new Table(
                stableId(text(item, "self_ref", "table")),
                title,
                headers,
                rows,
                integer(provenance, "page_no"),
                integer(provenance, "page_no"),
                boundingBox(provenance.path("bbox")),
                markdown);
    }

    private Asset toAsset(JsonNode item, Map<String, JsonNode> items) {
        List<String> captions = refs(item.path("captions"), items);
        JsonNode provenance = firstProvenance(item);
        String contentReference = text(item.path("image"), "uri",
                text(item.path("image"), "path", text(item, "self_ref", "")));
        return new Asset(
                stableId(text(item, "self_ref", "picture")),
                text(item, "label", "picture"),
                captions.isEmpty() ? "" : captions.getFirst(),
                contentReference,
                integer(provenance, "page_no"),
                boundingBox(provenance.path("bbox")));
    }

    private static String toMarkdown(List<String> headers, List<Row> rows) {
        if (headers.isEmpty()) {
            return "";
        }
        StringBuilder markdown = new StringBuilder();
        markdown.append("| ").append(String.join(" | ", headers)).append(" |\n");
        markdown.append("|").append("---|".repeat(headers.size())).append("\n");
        for (Row row : rows) {
            markdown.append("| ").append(String.join(" | ", row.cells())).append(" |\n");
        }
        return markdown.toString();
    }

    private static List<Table> mergeTableContinuations(List<Table> tables) {
        List<Table> merged = new ArrayList<>();
        for (Table current : tables) {
            if (!merged.isEmpty()) {
                Table previous = merged.getLast();
                if (isContinuation(previous, current)) {
                    merged.set(merged.size() - 1, merge(previous, current));
                    continue;
                }
            }
            merged.add(current);
        }
        return merged;
    }

    private static boolean isContinuation(Table previous, Table current) {
        if (previous.headers() == null || !previous.headers().equals(current.headers())) {
            return false;
        }
        if (current.title() != null && !current.title().isBlank()
                && !current.title().equals(previous.title())) {
            return false;
        }
        return previous.pageTo() != null
                && current.pageFrom() != null
                && current.pageFrom() == previous.pageTo() + 1;
    }

    private static Table merge(Table previous, Table current) {
        List<Row> sourceRows = new ArrayList<>(previous.rows());
        sourceRows.addAll(current.rows());
        List<Row> rows = new ArrayList<>();
        for (int index = 0; index < sourceRows.size(); index++) {
            Row row = sourceRows.get(index);
            rows.add(new Row(
                    row.id(), index, row.cells(), row.semanticText(), row.page(), row.boundingBox()));
        }
        return new Table(
                previous.id(),
                previous.title(),
                previous.headers(),
                rows,
                previous.pageFrom(),
                current.pageTo(),
                previous.boundingBox(),
                previous.markdown() + "\n" + current.markdown());
    }

    private static List<TableCell> tableCells(JsonNode cells) {
        List<TableCell> result = new ArrayList<>();
        if (!cells.isArray()) {
            return result;
        }
        for (JsonNode cell : cells) {
            int row = integer(firstPresent(cell,
                    "start_row_offset_idx", "start_row_offset", "row_idx", "row_offset"), 0);
            int column = integer(firstPresent(cell,
                    "start_col_offset_idx", "start_col_offset", "col_idx", "column_offset"), 0);
            result.add(new TableCell(
                    row,
                    column,
                    text(cell, "text", ""),
                    bool(cell, "column_header"),
                    bool(cell, "row_header")));
        }
        return result;
    }

    private static List<String> refs(JsonNode values, Map<String, JsonNode> items) {
        List<String> result = new ArrayList<>();
        if (!values.isArray()) {
            return result;
        }
        for (JsonNode value : values) {
            JsonNode item = items.get(text(value, "$ref", ""));
            if (item != null) {
                result.add(text(item, "text", text(item, "self_ref", "")));
            }
        }
        return result;
    }

    private static void indexItems(Map<String, JsonNode> items, JsonNode values, String collection) {
        if (!values.isArray()) {
            return;
        }
        int index = 0;
        for (JsonNode item : values) {
            String reference = text(item, "self_ref", "#/" + collection + "/" + index++);
            items.put(reference, item);
        }
    }

    private static JsonNode firstProvenance(JsonNode item) {
        JsonNode provenance = item.path("prov");
        return provenance.isArray() && !provenance.isEmpty() ? provenance.get(0) : item.path("prov");
    }

    private static BoundingBox boundingBox(JsonNode box) {
        if (!box.isObject()) {
            return null;
        }
        return new BoundingBox(
                decimal(box, "l"),
                decimal(box, "t"),
                decimal(box, "r"),
                decimal(box, "b"),
                text(box, "coord_origin", ""));
    }

    private static void appendPageText(Map<Integer, StringBuilder> pageText, Integer page, String content) {
        if (page == null || content == null || content.isBlank()) {
            return;
        }
        pageText.computeIfAbsent(page, ignored -> new StringBuilder())
                .append(content.trim()).append("\n\n");
    }

    private static Map<String, Object> metadata(JsonNode item) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String level = text(item, "level", "");
        if (!level.isBlank()) {
            metadata.put("level", level);
        }
        String contentLayer = text(item, "content_layer", "");
        if (!contentLayer.isBlank()) {
            metadata.put("contentLayer", contentLayer);
        }
        return metadata;
    }

    private static JsonNode firstPresent(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private static String itemType(String reference) {
        if (reference.contains("/tables/")) {
            return "table";
        }
        if (reference.contains("/pictures/")) {
            return "picture";
        }
        return "text";
    }

    private static String stableId(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        return reference.replaceFirst("^#/", "").replace('/', '-');
    }

    private static int sourceIndex(JsonNode node) {
        String reference = text(node, "self_ref", "");
        int slash = reference.lastIndexOf('/');
        try {
            return slash < 0 ? 0 : Integer.parseInt(reference.substring(slash + 1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String text(JsonNode node, String field, String defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : defaultValue;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static int integer(JsonNode node, int defaultValue) {
        return node == null || node.isNull() ? defaultValue : node.asInt(defaultValue);
    }

    private static double decimal(JsonNode node, String field) {
        return node.path(field).asDouble(0);
    }

    private static boolean bool(JsonNode node, String field) {
        return node.path(field).asBoolean(false);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record TableCell(int row, int column, String text, boolean columnHeader, boolean rowHeader) {}
}
