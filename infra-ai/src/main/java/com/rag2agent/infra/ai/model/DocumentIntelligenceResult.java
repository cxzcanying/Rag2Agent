package com.rag2agent.infra.ai.model;

import com.fasterxml.jackson.databind.JsonNode;

/** Docling Serve 单文件转换结果。 */
public record DocumentIntelligenceResult(
        String status,
        double processingTime,
        JsonNode document,
        String markdown,
        String text) {}
