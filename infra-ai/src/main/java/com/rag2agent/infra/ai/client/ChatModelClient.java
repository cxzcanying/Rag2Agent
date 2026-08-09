package com.rag2agent.infra.ai.client;

import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import java.util.function.Consumer;

public interface ChatModelClient {

    ChatCompletionResponse complete(ChatCompletionRequest request);

    /**
     * 流式对话：通过 SSE 增量回调文本片段。
     */
    void stream(ChatCompletionRequest request, Consumer<String> onDelta);
}
