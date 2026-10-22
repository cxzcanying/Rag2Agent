package com.rag2agent.bootstrap.dto;

import com.rag2agent.bootstrap.dto.TableDtos.BoundingBoxView;
import com.rag2agent.bootstrap.service.DocumentStructureRepository.ElementRecord;
import java.util.List;

public final class DocumentStructureDtos {

    private DocumentStructureDtos() {}

    public record ElementPage(
            long documentId,
            int version,
            long totalElements,
            int startOrdinal,
            int endOrdinal,
            boolean hasMore,
            String continuationCursor,
            List<ElementView> elements) {}

    public record ElementView(
            String elementId,
            String parentElementId,
            String type,
            int ordinal,
            Integer pageFrom,
            Integer pageTo,
            BoundingBoxView boundingBox,
            String text) {

        public static ElementView from(ElementRecord element) {
            return new ElementView(
                    element.elementId(),
                    element.parentElementId(),
                    element.type(),
                    element.ordinal(),
                    element.pageFrom(),
                    element.pageTo(),
                    element.boundingBox() == null
                            ? null
                            : new BoundingBoxView(
                                    element.boundingBox().left(),
                                    element.boundingBox().top(),
                                    element.boundingBox().right(),
                                    element.boundingBox().bottom(),
                                    element.boundingBox().coordinateOrigin()),
                    element.text());
        }
    }
}
