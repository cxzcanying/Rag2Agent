package com.rag2agent.infra.ai.model;

import java.util.List;

public record AiProviderDescriptor(String name, String baseUrl, List<String> capabilities, boolean enabled) {}
