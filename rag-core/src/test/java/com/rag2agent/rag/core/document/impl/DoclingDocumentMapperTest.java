package com.rag2agent.rag.core.document.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.rag.core.document.StructuredDocument;
import java.nio.charset.StandardCharsets;
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
        assertEquals("texts-0", result.elements().getFirst().id());
        assertEquals("title", result.elements().getFirst().type());
        assertEquals(1, result.elements().getFirst().pageFrom());
        assertEquals(1, result.tables().size());
        assertEquals("tables-0", result.tables().getFirst().id());
        assertEquals("收入表", result.tables().getFirst().title());
        assertEquals(java.util.List.of("项目", "金额"), result.tables().getFirst().headers());
        assertEquals("项目=收入；金额=100", result.tables().getFirst().rows().getFirst().semanticText());
        assertEquals(1, result.assets().size());
        assertEquals("pictures-0", result.assets().getFirst().id());
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

    @Test
    void decodesEmbeddedPictureContent() throws Exception {
        JsonNode document = mapper.readTree("""
                {
                  "schema_name": "DoclingDocument",
                  "version": "1.0",
                  "body": {"children": [{"$ref": "#/pictures/0"}]},
                  "texts": [],
                  "tables": [],
                  "pictures": [{
                    "self_ref": "#/pictures/0",
                    "label": "picture",
                    "image": {"uri": "data:image/png;base64,YWJj"},
                    "prov": [{"page_no": 1}]
                  }],
                  "pages": {"1": {"page_no": 1}}
                }
                """);

        StructuredDocument result = new DoclingDocumentMapper().map("doc-1", document, "");

        assertEquals("image/png", result.assets().getFirst().contentType());
        assertEquals("abc", new String(
                result.assets().getFirst().binary(), StandardCharsets.UTF_8));
        assertEquals(null, result.assets().getFirst().contentReference());
    }

    @Test
    void traversesNestedTextChildrenAndTablesFromHtml() throws Exception {
        JsonNode document = mapper.readTree("""
                {
                  "schema_name": "DoclingDocument",
                  "version": "1.0",
                  "body": {"children": [
                    {"$ref": "#/texts/0"},
                    {"$ref": "#/texts/1"}
                  ]},
                  "texts": [
                    {"self_ref": "#/texts/0", "label": "title", "text": "制度标题", "children": []},
                    {
                      "self_ref": "#/texts/1",
                      "label": "title",
                      "text": "制度标题",
                      "children": [
                        {"$ref": "#/texts/2"},
                        {"$ref": "#/texts/3"},
                        {"$ref": "#/tables/0"}
                      ]
                    },
                    {"self_ref": "#/texts/2", "label": "text", "text": "高级工程师年假为 10 天", "children": []},
                    {"self_ref": "#/texts/3", "label": "text", "text": "制度由星河科技发布", "children": []}
                  ],
                  "tables": [{
                    "self_ref": "#/tables/0",
                    "label": "table",
                    "captions": [{"$ref": "#/texts/2"}],
                    "children": [],
                    "data": {"table_cells": [
                      {"text": "职级", "start_row_offset_idx": 0, "start_col_offset_idx": 0, "column_header": true},
                      {"text": "天数", "start_row_offset_idx": 0, "start_col_offset_idx": 1, "column_header": true},
                      {"text": "高级工程师", "start_row_offset_idx": 1, "start_col_offset_idx": 0},
                      {"text": "10 天", "start_row_offset_idx": 1, "start_col_offset_idx": 1}
                    ]}
                  }],
                  "pictures": [],
                  "groups": [],
                  "pages": {}
                }
                """);

        StructuredDocument result = new DoclingDocumentMapper().map("doc-html", document, "");

        assertEquals(5, result.elements().size());
        assertTrue(result.elements().stream().anyMatch(element -> element.text().contains("星河科技")));
        assertEquals(1, result.tables().size());
        assertEquals("职级=高级工程师；天数=10 天",
                result.tables().getFirst().rows().getFirst().semanticText());
    }
}
