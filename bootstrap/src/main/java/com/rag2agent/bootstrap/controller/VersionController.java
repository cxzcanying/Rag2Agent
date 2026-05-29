package com.rag2agent.bootstrap.controller;

import com.rag2agent.bootstrap.config.Rag2AgentProperties;
import com.rag2agent.framework.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/version")
public class VersionController {

    private final Rag2AgentProperties properties;

    public VersionController(Rag2AgentProperties properties) {
        this.properties = properties;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> version() {
        return ApiResponse.success(Map.of(
                "name", properties.getName(),
                "version", properties.getVersion(),
                "modules", properties.getModules()));
    }
}
