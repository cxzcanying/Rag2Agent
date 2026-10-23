package com.rag2agent.bootstrap.evaluation;

import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.bootstrap.mapper.DocumentMetaMapper;
import com.rag2agent.bootstrap.service.DocumentStructureRepository;
import com.rag2agent.rag.core.retrieval.RetrievalResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 按评测场景读取结构化证据；检索元数据只作为补充，不作为唯一事实源。 */
@Service
public class EvaluationScenarioEvidenceService {

    private final DocumentMetaMapper documentMapper;
    private final DocumentStructureRepository structureRepository;

    public EvaluationScenarioEvidenceService(
            DocumentMetaMapper documentMapper,
            DocumentStructureRepository structureRepository) {
        this.documentMapper = documentMapper;
        this.structureRepository = structureRepository;
    }

    public List<RetrievalResult> augment(
            EvaluationRepository.EvalCase evalCase,
            List<RetrievalResult> retrieved) {
        String type = evalCase.caseType() == null
                ? "RAG" : evalCase.caseType().toUpperCase(java.util.Locale.ROOT);
        List<RetrievalResult> scenario = switch (type) {
            case "TABLE" -> tableEvidence(evalCase);
            case "MULTIMODAL" -> assetEvidence(evalCase);
            default -> List.of();
        };
        if (scenario.isEmpty()) {
            return retrieved;
        }
        List<RetrievalResult> combined = new ArrayList<>(scenario);
        combined.addAll(retrieved);
        return List.copyOf(combined);
    }

    private List<RetrievalResult> tableEvidence(EvaluationRepository.EvalCase evalCase) {
        Map<String, Object> expected = evalCase.evidence();
        if (!(expected.get("tableId") instanceof String tableId) || tableId.isBlank()) {
            return List.of();
        }
        List<String> rowIds = stringList(expected.get("rowIds"));
        for (Long documentId : evalCase.goldenDocumentIds()) {
            DocumentMeta document = readableDocument(documentId, evalCase.kbId());
            if (document == null || document.getVersion() == null) {
                continue;
            }
            var table = structureRepository.findTable(
                    documentId, document.getVersion(), tableId);
            if (table.isEmpty()) {
                continue;
            }
            var rows = structureRepository.findRowsByIds(
                    documentId, document.getVersion(), tableId, rowIds);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("documentId", documentId);
            metadata.put("version", document.getVersion());
            metadata.put("contentType", "table");
            metadata.put("tableId", tableId);
            metadata.put("headers", table.get().headers());
            metadata.put("rowIds", rows.stream()
                    .map(DocumentStructureRepository.RowRecord::rowId).toList());
            metadata.put("rows", rows.stream()
                    .map(DocumentStructureRepository.RowRecord::semanticText).toList());
            String content = table.get().title() + "\n"
                    + String.join(" | ", table.get().headers()) + "\n"
                    + String.join("\n", rows.stream()
                            .map(DocumentStructureRepository.RowRecord::semanticText).toList());
            return List.of(new RetrievalResult(
                    "scenario-table-" + documentId + "-" + tableId,
                    content, 1.0, Map.copyOf(metadata)));
        }
        return List.of();
    }

    private List<RetrievalResult> assetEvidence(EvaluationRepository.EvalCase evalCase) {
        List<String> expectedIds = stringList(evalCase.evidence().get("assetIds"));
        if (expectedIds.isEmpty()) {
            return List.of();
        }
        List<String> foundIds = new ArrayList<>();
        List<Long> documentIds = new ArrayList<>();
        List<String> descriptions = new ArrayList<>();
        for (Long documentId : evalCase.goldenDocumentIds()) {
            DocumentMeta document = readableDocument(documentId, evalCase.kbId());
            if (document == null || document.getVersion() == null) {
                continue;
            }
            for (String assetId : expectedIds) {
                var asset = structureRepository.findAsset(
                        documentId, document.getVersion(), assetId);
                if (asset.isEmpty()) {
                    continue;
                }
                foundIds.add(asset.get().assetId());
                documentIds.add(documentId);
                if (asset.get().visionText() != null && !asset.get().visionText().isBlank()) {
                    descriptions.add(asset.get().visionText());
                }
            }
        }
        if (foundIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("contentType", "asset");
        metadata.put("assetIds", foundIds);
        metadata.put("documentId", documentIds.getFirst());
        metadata.put("documentIds", documentIds);
        metadata.put("described", !descriptions.isEmpty());
        return List.of(new RetrievalResult(
                "scenario-assets", String.join("\n", descriptions), 1.0,
                Map.copyOf(metadata)));
    }

    private DocumentMeta readableDocument(Long documentId, Long kbId) {
        if (documentId == null) {
            return null;
        }
        DocumentMeta document = documentMapper.selectById(documentId);
        return document != null && kbId.equals(document.getKbId()) ? document : null;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
