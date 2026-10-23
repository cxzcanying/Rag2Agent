package com.rag2agent.rag.core.document.impl;

import com.rag2agent.rag.core.document.ParsedDocument;
import com.rag2agent.rag.core.document.StructuredDocument;
import com.rag2agent.rag.core.document.StructuredDocument.Row;
import com.rag2agent.rag.core.document.StructuredDocument.Table;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row.MissingCellPolicy;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;

/** Excel 流式读取：逐表读取行列，保留显示值、原始值、公式和合并区域。 */
public final class ExcelDocumentParser {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    public StructuredDocument parse(String documentId, String fileName, Path file) {
        try (Workbook workbook = WorkbookFactory.create(file.toFile(), null, true)) {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<String> pages = new ArrayList<>();
            List<Table> tables = new ArrayList<>();
            List<Map<String, Object>> sheetMetadata = new ArrayList<>();
            int formulaCount = 0;
            int mergedCount = 0;
            StringBuilder text = new StringBuilder();

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                int maxColumns = maxColumns(sheet);
                int headerRows = headerRows(sheet, maxColumns);
                List<String> headers = headers(sheet, headerRows, maxColumns, formatter, evaluator);
                List<Row> rows = new ArrayList<>();
                StringBuilder sheetText = new StringBuilder(sheet.getSheetName()).append('\n');
                for (int rowIndex = headerRows; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    org.apache.poi.ss.usermodel.Row row = sheet.getRow(rowIndex);
                    if (row == null) {
                        continue;
                    }
                    List<String> cells = new ArrayList<>();
                    Map<String, Object> rawValues = new LinkedHashMap<>();
                    for (int column = 0; column < maxColumns; column++) {
                        Cell cell = row.getCell(column, MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        String display = cell == null ? "" : display(cell, formatter, evaluator);
                        String original = cell == null ? "" : original(cell);
                        String formula = cell == null ? "" : formulaOf(cell);
                        if (!formula.isBlank()) {
                            formulaCount++;
                        }
                        cells.add(display);
                        rawValues.put("col" + column,
                                Map.of("original", original, "display", display, "formula", formula));
                    }
                    String semantic = semantic(headers, cells, rawValues);
                    rows.add(new Row("r" + (rowIndex + 1), rowIndex, List.copyOf(cells),
                            semantic, sheetIndex + 1, null));
                    sheetText.append(semantic).append('\n');
                }
                List<String> merged = new ArrayList<>();
                for (CellRangeAddress region : sheet.getMergedRegions()) {
                    mergedCount++;
                    merged.add(region.formatAsString());
                }
                tables.add(new Table(
                        "sheet-" + (sheetIndex + 1), sheet.getSheetName(), headers, rows,
                        sheetIndex + 1, sheetIndex + 1, null, sheetText.toString()));
                pages.add(sheetText.toString());
                text.append(sheetText).append('\n');
                sheetMetadata.add(Map.of(
                        "name", sheet.getSheetName(),
                        "rows", Math.max(0, sheet.getLastRowNum() - headerRows + 1),
                        "columns", maxColumns,
                        "headerRows", headerRows,
                        "mergedRegions", merged));
            }

            ParsedDocument parsed = new ParsedDocument(
                    documentId,
                    text.toString().trim(),
                    pages,
                    Map.of(
                            "pageCount", workbook.getNumberOfSheets(),
                            "sheets", sheetMetadata,
                            "formulaCount", formulaCount,
                            "mergedRegionCount", mergedCount));
            Map<String, Object> metadata = new LinkedHashMap<>(parsed.metadata());
            metadata.put("parser", "apache-poi");
            metadata.put("elementCount", tables.size());
            metadata.put("tableCount", tables.size());
            metadata.put("assetCount", 0);
            return new StructuredDocument(
                    documentId, parsed.text(), parsed.text(), parsed.pages(),
                    List.of(), tables, List.of(), metadata);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Excel 解析失败: " + fileName, exception);
        }
    }

    private int maxColumns(Sheet sheet) {
        int max = 0;
        for (org.apache.poi.ss.usermodel.Row row : sheet) {
            max = Math.max(max, row.getLastCellNum());
        }
        return Math.max(1, max);
    }

    private int headerRows(Sheet sheet, int maxColumns) {
        int headerRows = 1;
        for (int rowIndex = 1; rowIndex <= Math.min(2, sheet.getLastRowNum()); rowIndex++) {
            org.apache.poi.ss.usermodel.Row row = sheet.getRow(rowIndex);
            if (row == null || numericCells(row, maxColumns) > maxColumns / 2) {
                break;
            }
            headerRows++;
        }
        return Math.max(1, headerRows);
    }

    private int numericCells(org.apache.poi.ss.usermodel.Row row, int maxColumns) {
        int count = 0;
        for (int i = 0; i < maxColumns; i++) {
            Cell cell = row.getCell(i, MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && (cell.getCellType() == CellType.NUMERIC
                    || (cell.getCellType() == CellType.FORMULA
                        && cell.getCachedFormulaResultType() == CellType.NUMERIC))) {
                count++;
            }
        }
        return count;
    }

    private List<String> headers(
            Sheet sheet, int headerRows, int maxColumns,
            DataFormatter formatter, FormulaEvaluator evaluator) {
        List<String> headers = new ArrayList<>();
        for (int column = 0; column < maxColumns; column++) {
            List<String> parts = new ArrayList<>();
            for (int row = 0; row < headerRows; row++) {
                org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(row);
                Cell cell = headerRow == null
                        ? null : headerRow.getCell(column, MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = cell == null ? "" : display(cell, formatter, evaluator).trim();
                if (!value.isBlank()) {
                    parts.add(value);
                }
            }
            headers.add(parts.isEmpty() ? "列" + (column + 1) : String.join(" / ", parts));
        }
        return List.copyOf(headers);
    }

    private String semantic(List<String> headers, List<String> cells, Map<String, Object> rawValues) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) {
                value.append("; ");
            }
            value.append(headers.get(i)).append('=').append(cells.get(i));
            Map<?, ?> raw = (Map<?, ?>) rawValues.get("col" + i);
            if (raw != null && !String.valueOf(raw.get("formula")).isBlank()) {
                value.append(" [公式=").append(raw.get("formula")).append(']');
            }
        }
        return value.toString();
    }

    private String display(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell.getCellType() == CellType.NUMERIC) {
            if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                return DATE_FORMAT.format(cell.getLocalDateTimeCellValue().toLocalDate());
            }
            return formatter.formatRawCellContents(
                    cell.getNumericCellValue(), cell.getCellStyle().getDataFormat(),
                    cell.getCellStyle().getDataFormatString());
        }
        return formatter.formatCellValue(cell, evaluator);
    }

    private String original(Cell cell) {
        return switch (cell.getCellType()) {
            case NUMERIC -> org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toString()
                    : Double.toString(cell.getNumericCellValue());
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case FORMULA -> "formula:" + cell.getCellFormula();
            default -> cell.toString();
        };
    }

    private String formulaOf(Cell cell) {
        return cell.getCellType() == CellType.FORMULA ? cell.getCellFormula() : "";
    }
}
