package com.rag2agent.infra.ai.client;

import com.rag2agent.infra.ai.model.VectorSearchRequest;
import com.rag2agent.infra.ai.model.VectorSearchResponse;

public interface VectorStoreClient {

    VectorSearchResponse search(VectorSearchRequest request);
}
