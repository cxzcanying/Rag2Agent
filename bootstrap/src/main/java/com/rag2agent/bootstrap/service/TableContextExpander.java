package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.service.DocumentStructureRepository.RowRecord;
import com.rag2agent.bootstrap.service.DocumentStructureRepository.TableRecord;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 为命中的表格行补充表头、相邻行和页码范围。 */
@Service
public class TableContextExpander {

    private static final int NEIGHBOR_RADIUS = 2;

    private final DocumentStructureRepository repository;

    public TableContextExpander(DocumentStructureRepository repository) {
        this.repository = repository;
    }

    public RetrievalResult expand(RetrievalResult result) {
        Map<String, Object> metadata = result.metadata();
        if (!"table_row".equals(metadata.get("contentType"))) {
            return result;
        }
        Long documentId = number(metadata.get("documentId"));
        Long version = number(metadata.get("version"));
        String tableId = string(metadata.get("tableId"));
        Integer rowIndex = integer(metadata.get("rowIndex"));
        if (documentId == null || version == null || tableId == null || rowIndex == null) {
            return result;
        }
        TableRecord table = repository.findTable(documentId, version.intValue(), tableId).orElse(null);
        if (table == null) {
            return result;
        }
        var rows = repository.findRowsAround(
                documentId, version.intValue(), tableId, rowIndex, NEIGHBOR_RADIUS);
        StringBuilder content = new StringBuilder();
        content.append("表标题：").append(table.title() == null || table.title().isBlank()
                ? tableId : table.title()).append('\n');
        content.append("表头：").append(String.join(" | ", table.headers())).append('\n');
        content.append("页码范围：").append(pageRange(table.pageFrom(), table.pageTo())).append('\n');
        content.append("相关行：\n");
        for (RowRecord row : rows) {
            content.append(row.rowIndex() == rowIndex ? "-> " : "   ")
                    .append(row.semanticText())
                    .append("（").append(pageLabel(row.pageNo())).append("）");
            if (row.rowIndex() == rowIndex) {
                content.append(" [命中]");
            }
            content.append('\n');
        }
        Map<String, Object> expanded = new LinkedHashMap<>(metadata);
        expanded.put("expandedContent", content.toString().trim());
        expanded.put("tableHeaders", table.headers());
        expanded.put("neighborRowCount", rows.size());
        return new RetrievalResult(result.chunkId(), result.content(), result.score(), expanded);
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String string(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private String pageRange(Integer from, Integer to) {
        if (from == null && to == null) {
            return "无分页信息";
        }
        if (from == null || to == null || from.equals(to)) {
            return pageLabel(from == null ? to : from);
        }
        return "第" + from + "-" + to + "页";
    }

    private String pageLabel(Integer page) {
        return page == null ? "页码未知" : "第" + page + "页";
    }
}
