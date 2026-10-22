package com.rag2agent.bootstrap.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.rag2agent.bootstrap.dto.DocumentStructureDtos.ElementPage;
import com.rag2agent.bootstrap.dto.AssetDtos.AssetView;
import com.rag2agent.bootstrap.dto.TableDtos.TablePage;
import com.rag2agent.bootstrap.service.DocumentElementQueryService;
import com.rag2agent.bootstrap.service.DocumentAssetService;
import com.rag2agent.bootstrap.service.TableQueryService;
import com.rag2agent.framework.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents/{documentId}")
public class DocumentStructureController {

    private final TableQueryService tableQueryService;
    private final DocumentElementQueryService elementQueryService;
    private final DocumentAssetService assetService;

    public DocumentStructureController(
            TableQueryService tableQueryService,
            DocumentElementQueryService elementQueryService,
            DocumentAssetService assetService) {
        this.tableQueryService = tableQueryService;
        this.elementQueryService = elementQueryService;
        this.assetService = assetService;
    }

    @GetMapping("/tables/{tableId}/rows")
    public ApiResponse<TablePage> rows(
            @PathVariable("documentId") Long documentId,
            @PathVariable("tableId") String tableId,
            @RequestParam(value = "version", required = false) Integer version,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return ApiResponse.success(tableQueryService.page(
                StpUtil.getLoginIdAsLong(), documentId, tableId, version, cursor, limit));
    }

    @GetMapping("/elements")
    public ApiResponse<ElementPage> elements(
            @PathVariable("documentId") Long documentId,
            @RequestParam(value = "version", required = false) Integer version,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return ApiResponse.success(elementQueryService.page(
                StpUtil.getLoginIdAsLong(), documentId, version, cursor, limit));
    }

    @GetMapping("/assets/{assetId}")
    public ApiResponse<AssetView> asset(
            @PathVariable("documentId") Long documentId,
            @PathVariable("assetId") String assetId,
            @RequestParam(value = "version", required = false) Integer version) {
        return ApiResponse.success(assetService.presign(
                StpUtil.getLoginIdAsLong(), documentId, assetId, version));
    }
}
