package com.rag2agent.bootstrap.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.rag2agent.bootstrap.dto.TableDtos.TablePage;
import com.rag2agent.bootstrap.service.TableQueryService;
import com.rag2agent.framework.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents/{documentId}/tables")
public class DocumentStructureController {

    private final TableQueryService tableQueryService;

    public DocumentStructureController(TableQueryService tableQueryService) {
        this.tableQueryService = tableQueryService;
    }

    @GetMapping("/{tableId}/rows")
    public ApiResponse<TablePage> rows(
            @PathVariable("documentId") Long documentId,
            @PathVariable("tableId") String tableId,
            @RequestParam(value = "version", required = false) Integer version,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return ApiResponse.success(tableQueryService.page(
                StpUtil.getLoginIdAsLong(), documentId, tableId, version, cursor, limit));
    }
}
