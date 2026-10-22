package com.rag2agent.bootstrap.dto;

import com.rag2agent.bootstrap.service.DocumentStructureRepository.RowRecord;
import com.rag2agent.rag.core.document.StructuredDocument.BoundingBox;
import java.util.List;

public final class TableDtos {

    private TableDtos() {}

    public record TablePage(
            String tableId,
            String title,
            List<String> headers,
            Integer pageFrom,
            Integer pageTo,
            long totalRows,
            int rowStart,
            int rowEnd,
            boolean hasMore,
            String continuationCursor,
            List<TableRowView> rows) {}

    public record TableRowView(
            String rowId,
            int rowIndex,
            List<String> cells,
            String semanticText,
            Integer pageNo,
            BoundingBoxView boundingBox) {

        public static TableRowView from(RowRecord row) {
            return new TableRowView(
                    row.rowId(),
                    row.rowIndex(),
                    row.cells(),
                    row.semanticText(),
                    row.pageNo(),
                    BoundingBoxView.from(row.boundingBox()));
        }
    }

    public record BoundingBoxView(double left, double top, double right, double bottom, String coordinateOrigin) {
        static BoundingBoxView from(BoundingBox box) {
            return box == null
                    ? null
                    : new BoundingBoxView(
                            box.left(), box.top(), box.right(), box.bottom(), box.coordinateOrigin());
        }
    }
}
