package com.rag2agent.infra.ai.client;

import com.rag2agent.infra.ai.model.VisionRequest;
import com.rag2agent.infra.ai.model.VisionResult;

public interface VisionClient {

    boolean available();

    VisionResult describe(VisionRequest request);
}
