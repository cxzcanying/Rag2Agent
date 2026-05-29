package com.rag2agent.bootstrap.controller;

import com.rag2agent.framework.common.ApiResponse;
import com.rag2agent.infra.ai.config.AiProviderProperties;
import com.rag2agent.infra.ai.model.AiProviderDescriptor;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/providers")
public class AiProviderController {

    private final AiProviderProperties properties;

    public AiProviderController(AiProviderProperties properties) {
        this.properties = properties;
    }

    @GetMapping
    public ApiResponse<List<AiProviderDescriptor>> listProviders() {
        List<AiProviderDescriptor> providers = properties.getProviders().stream()
                .map(provider -> new AiProviderDescriptor(
                        provider.getName(), provider.getBaseUrl(), provider.getCapabilities(), provider.isEnabled()))
                .toList();
        return ApiResponse.success(providers);
    }
}
