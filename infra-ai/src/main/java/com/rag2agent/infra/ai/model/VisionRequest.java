package com.rag2agent.infra.ai.model;

public record VisionRequest(String prompt, byte[] image, String contentType) {

    public VisionRequest {
        image = image == null ? new byte[0] : image.clone();
    }
}
