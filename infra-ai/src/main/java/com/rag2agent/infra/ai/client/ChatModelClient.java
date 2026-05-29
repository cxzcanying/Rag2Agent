package com.rag2agent.infra.ai.client;

import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;

public interface ChatModelClient {

    ChatCompletionResponse complete(ChatCompletionRequest request);
}
