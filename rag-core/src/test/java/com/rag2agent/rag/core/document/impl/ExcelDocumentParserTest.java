package com.rag2agent.rag.core.document.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelDocumentParserTest {

    @Test
    void preservesSheetsHeadersFormulasAndMergedCells() throws Exception {
        Path file = Files.createTempFile("rag2agent-excel-", ".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("营收");
            sheet.createRow(0).createCell(0).setCellValue("区域");
            sheet.getRow(0).createCell(1).setCellValue("2026");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("华东");
            data.createCell(1).setCellValue(100);
            data.createCell(2).setCellFormula("B2*2");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 1, 0, 1));
            try (var output = Files.newOutputStream(file)) {
                workbook.write(output);
            }
        }
        var document = new ExcelDocumentParser().parse("doc-1", "revenue.xlsx", file);
        assertEquals("apache-poi", document.metadata().get("parser"));
        assertEquals(1, document.tables().size());
        assertEquals("区域", document.tables().getFirst().headers().getFirst());
        assertTrue(document.tables().getFirst().rows().getFirst().semanticText().contains("公式=B2*2"));
        assertEquals(1, document.metadata().get("mergedRegionCount"));
        Files.deleteIfExists(file);
    }
}
