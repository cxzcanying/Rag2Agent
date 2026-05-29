package com.rag2agent.infra.ai.client;

import com.rag2agent.infra.ai.model.EmbeddingRequest;
import com.rag2agent.infra.ai.model.EmbeddingResponse;

public interface EmbeddingClient {

    EmbeddingResponse embed(EmbeddingRequest request);
}
