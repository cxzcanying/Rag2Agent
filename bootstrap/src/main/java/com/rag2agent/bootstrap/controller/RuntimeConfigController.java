package com.rag2agent.bootstrap.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.rag2agent.bootstrap.config.RuntimeConfigService;
import com.rag2agent.framework.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime-config")
public class RuntimeConfigController {

    private final RuntimeConfigService service;

    public RuntimeConfigController(RuntimeConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        StpUtil.checkLogin();
        return ApiResponse.success(service.snapshot());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> values) {
        StpUtil.checkLogin();
        return ApiResponse.success(service.update(values));
    }
}
