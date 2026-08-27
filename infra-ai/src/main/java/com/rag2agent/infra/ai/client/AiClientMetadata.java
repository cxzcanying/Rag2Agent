package com.rag2agent.infra.ai.client;

/** 客户端实际使用的 provider/model 元数据，供请求标识、缓存隔离和可观测标签复用。 */
public interface AiClientMetadata {

    default String providerName() {
        return "unknown";
    }

    default String modelName() {
        return "configured";
    }
}
