package com.rag2agent.bootstrap.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.rag.core.document.StructuredDocument;
import com.rag2agent.rag.core.document.StructuredDocument.Asset;
import com.rag2agent.rag.core.document.StructuredDocument.BoundingBox;
import com.rag2agent.rag.core.document.StructuredDocument.Element;
import com.rag2agent.rag.core.document.StructuredDocument.Row;
import com.rag2agent.rag.core.document.StructuredDocument.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 文档元素、表格、表格行和资产的版本化持久化。 */
@Repository
public class DocumentStructureRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DocumentStructureRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public void replace(DocumentMeta document, StructuredDocument structured, int version) {
        deleteVersion(document.getId(), version);

        List<Object[]> elements = new ArrayList<>();
        for (Element element : structured.elements()) {
            elements.add(new Object[] {
                    element.id(), document.getId(), document.getKbId(), version, element.parentId(),
                    element.type(), element.ordinal(), element.pageFrom(), element.pageTo(),
                    bbox(element.boundingBox()), element.text(), json(element.metadata())
            });
        }
        jdbc.batchUpdate("""
                INSERT INTO document_element
                    (element_id, document_id, kb_id, version, parent_element_id, element_type, ordinal,
                     page_from, page_to, bbox, text_content, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb))
                """, elements);

        List<Object[]> tables = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (Table table : structured.tables()) {
            tables.add(new Object[] {
                    table.id(), document.getId(), document.getKbId(), version, table.title(),
                    json(table.headers()), table.pageFrom(), table.pageTo(), bbox(table.boundingBox())
            });
            for (Row row : table.rows()) {
                rows.add(new Object[] {
                        row.id(), table.id(), document.getId(), document.getKbId(), version,
                        row.ordinal(), json(row.cells()), row.semanticText(),
                        row.page(), bbox(row.boundingBox())
                });
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO document_table
                    (table_id, document_id, kb_id, version, title, headers, page_from, page_to, bbox)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb))
                """, tables);
        jdbc.batchUpdate("""
                INSERT INTO document_table_row
                    (row_id, table_id, document_id, kb_id, version, row_index, cells,
                     semantic_text, page_no, bbox)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb))
                """, rows);

        List<Object[]> assets = new ArrayList<>();
        for (Asset asset : structured.assets()) {
            assets.add(new Object[] {
                    asset.id(), document.getId(), document.getKbId(), version, asset.id(),
                    asset.type(), asset.title(), asset.contentReference(), asset.page(),
                    bbox(asset.boundingBox()), "{}"
            });
        }
        jdbc.batchUpdate("""
                INSERT INTO document_asset
                    (asset_id, document_id, kb_id, version, source_element_id, asset_type,
                     title, content_reference, page_no, bbox, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                """, assets);
    }

    @Transactional
    public void deleteBelowVersion(Long documentId, int version) {
        jdbc.update("DELETE FROM document_table_row WHERE document_id = ? AND version < ?", documentId, version);
        jdbc.update("DELETE FROM document_table WHERE document_id = ? AND version < ?", documentId, version);
        jdbc.update("DELETE FROM document_asset WHERE document_id = ? AND version < ?", documentId, version);
        jdbc.update("DELETE FROM document_element WHERE document_id = ? AND version < ?", documentId, version);
    }

    private void deleteVersion(Long documentId, int version) {
        jdbc.update("DELETE FROM document_table_row WHERE document_id = ? AND version = ?", documentId, version);
        jdbc.update("DELETE FROM document_table WHERE document_id = ? AND version = ?", documentId, version);
        jdbc.update("DELETE FROM document_asset WHERE document_id = ? AND version = ?", documentId, version);
        jdbc.update("DELETE FROM document_element WHERE document_id = ? AND version = ?", documentId, version);
    }

    private String bbox(BoundingBox box) {
        return box == null ? null : json(Map.of(
                "l", box.left(),
                "t", box.top(),
                "r", box.right(),
                "b", box.bottom(),
                "coord_origin", box.coordinateOrigin() == null ? "" : box.coordinateOrigin()));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("文档结构 JSON 序列化失败", exception);
        }
    }
}
