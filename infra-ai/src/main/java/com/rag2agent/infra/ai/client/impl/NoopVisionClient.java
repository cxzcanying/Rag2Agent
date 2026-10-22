package com.rag2agent.infra.ai.client.impl;

import com.rag2agent.infra.ai.client.VisionClient;
import com.rag2agent.infra.ai.model.VisionRequest;
import com.rag2agent.infra.ai.model.VisionResult;

public class NoopVisionClient implements VisionClient {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public VisionResult describe(VisionRequest request) {
        throw new IllegalStateException("Vision provider 未启用");
    }
}
