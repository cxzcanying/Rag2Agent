package com.rag2agent.infra.ai.client;

import com.rag2agent.infra.ai.model.RerankRequest;
import com.rag2agent.infra.ai.model.RerankResponse;

public interface RerankClient {

    RerankResponse rerank(RerankRequest request);
}
