package com.rag2agent.rag.core.document.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.rag.core.document.StructuredDocument;
import org.junit.jupiter.api.Test;

class DoclingDocumentMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsReadingOrderTablesAndProvenance() throws Exception {
        JsonNode document = mapper.readTree("""
                {
                  "schema_name": "DoclingDocument",
                  "version": "1.0",
                  "body": {"self_ref": "#/body", "children": [{"$ref": "#/groups/0"}]},
                  "groups": [{"self_ref": "#/groups/0", "children": [
                    {"$ref": "#/texts/0"},
                    {"$ref": "#/tables/0"},
                    {"$ref": "#/pictures/0"}
                  ]}],
                  "texts": [
                    {
                      "self_ref": "#/texts/0",
                      "parent": {"$ref": "#/body"},
                      "label": "title",
                      "level": 1,
                      "text": "年度报告",
                      "prov": [{"page_no": 1, "bbox": {"l": 10, "t": 20, "r": 100, "b": 40, "coord_origin": "TOPLEFT"}}]
                    },
                    {
                      "self_ref": "#/texts/1",
                      "parent": {"$ref": "#/body"},
                      "label": "caption",
                      "text": "收入表"
                    }
                  ],
                  "tables": [{
                    "self_ref": "#/tables/0",
                    "label": "table",
                    "captions": [{"$ref": "#/texts/1"}],
                    "prov": [{"page_no": 2, "bbox": {"l": 10, "t": 100, "r": 300, "b": 200, "coord_origin": "TOPLEFT"}}],
                    "data": {"table_cells": [
                      {"text": "项目", "start_row_offset_idx": 0, "start_col_offset_idx": 0, "column_header": true},
                      {"text": "金额", "start_row_offset_idx": 0, "start_col_offset_idx": 1, "column_header": true},
                      {"text": "收入", "start_row_offset_idx": 1, "start_col_offset_idx": 0},
                      {"text": "100", "start_row_offset_idx": 1, "start_col_offset_idx": 1}
                    ]}
                  }],
                  "pictures": [{
                    "self_ref": "#/pictures/0",
                    "label": "picture",
                    "captions": [{"$ref": "#/texts/1"}],
                    "image": {"uri": "images/table.png"},
                    "prov": [{"page_no": 2, "bbox": {"l": 20, "t": 220, "r": 200, "b": 300, "coord_origin": "TOPLEFT"}}]
                  }],
                  "pages": {"1": {"page_no": 1}, "2": {"page_no": 2}}
                }
                """);

        StructuredDocument result = new DoclingDocumentMapper().map("doc-1", document, "年度报告\n\n收入表");

        assertEquals(3, result.elements().size());
        assertEquals("title", result.elements().getFirst().type());
        assertEquals(1, result.elements().getFirst().pageFrom());
        assertEquals(1, result.tables().size());
        assertEquals("收入表", result.tables().getFirst().title());
        assertEquals(java.util.List.of("项目", "金额"), result.tables().getFirst().headers());
        assertEquals("项目=收入；金额=100", result.tables().getFirst().rows().getFirst().semanticText());
        assertEquals(1, result.assets().size());
        assertEquals("images/table.png", result.assets().getFirst().contentReference());
        assertEquals(2, result.pages().size());
        assertEquals("docling", result.metadata().get("parser"));
        assertTrue(result.text().contains("收入表"));
    }

    @Test
    void mergesAdjacentTablesWithSameHeadersAndKeepsPhysicalPages() throws Exception {
        JsonNode document = mapper.readTree("""
                {
                  "schema_name": "DoclingDocument",
                  "version": "1.0",
                  "body": {"children": [
                    {"$ref": "#/tables/0"},
                    {"$ref": "#/tables/1"}
                  ]},
                  "texts": [],
                  "pictures": [],
                  "tables": [
                    {
                      "self_ref": "#/tables/0",
                      "prov": [{"page_no": 2}],
                      "data": {"table_cells": [
                        {"text": "项目", "start_row_offset_idx": 0, "start_col_offset_idx": 0, "column_header": true},
                        {"text": "金额", "start_row_offset_idx": 0, "start_col_offset_idx": 1, "column_header": true},
                        {"text": "收入", "start_row_offset_idx": 1, "start_col_offset_idx": 0},
                        {"text": "100", "start_row_offset_idx": 1, "start_col_offset_idx": 1}
                      ]}
                    },
                    {
                      "self_ref": "#/tables/1",
                      "prov": [{"page_no": 3}],
                      "data": {"table_cells": [
                        {"text": "项目", "start_row_offset_idx": 0, "start_col_offset_idx": 0, "column_header": true},
                        {"text": "金额", "start_row_offset_idx": 0, "start_col_offset_idx": 1, "column_header": true},
                        {"text": "成本", "start_row_offset_idx": 1, "start_col_offset_idx": 0},
                        {"text": "60", "start_row_offset_idx": 1, "start_col_offset_idx": 1}
                      ]}
                    }
                  ],
                  "pages": {"2": {"page_no": 2}, "3": {"page_no": 3}}
                }
                """);

        StructuredDocument result = new DoclingDocumentMapper().map("doc-1", document, "");

        assertEquals(1, result.tables().size());
        assertEquals(2, result.tables().getFirst().pageFrom());
        assertEquals(3, result.tables().getFirst().pageTo());
        assertEquals(2, result.tables().getFirst().rows().size());
        assertEquals(2, result.tables().getFirst().rows().get(0).page());
        assertEquals(3, result.tables().getFirst().rows().get(1).page());
    }
}
