package com.rag2agent.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rag2agent.infra.ai.client.ChatModelClient;
import com.rag2agent.infra.ai.client.EmbeddingClient;
import com.rag2agent.infra.ai.client.RerankClient;
import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import com.rag2agent.infra.ai.model.ChatMessage;
import com.rag2agent.infra.ai.model.EmbeddingRequest;
import com.rag2agent.infra.ai.model.EmbeddingResponse;
import com.rag2agent.infra.ai.model.RerankRequest;
import com.rag2agent.infra.ai.model.RerankResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StringUtils;

/**
 * 真实模型调用集成测试（IT 结尾，surefire 默认不执行，不参与 CI）：
 * mvn -pl bootstrap -am test -Dtest=AiClientRealIT
 * 依赖本地 .env 中的 DeepSeek / 硅基流动 key 与运行中的中间件。
 */
@SpringBootTest
@ActiveProfiles("dev")
class AiClientRealIT {

    @Autowired
    private ChatModelClient chatModelClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private RerankClient rerankClient;

    @Test
    void chat_realDeepSeekCall() {
        ChatCompletionResponse response = chatModelClient.complete(new ChatCompletionRequest(
                "deepseek",
                null,
                List.of(new ChatMessage("user", "用一句话回答：1+1等于几？")),
                Map.of()));
        assertTrue(StringUtils.hasText(response.content()));
        System.out.println("CHAT_REAL: " + response.content());
    }

    @Test
    void embedding_realBgeM3Call() {
        EmbeddingResponse response =
                embeddingClient.embed(new EmbeddingRequest("siliconflow", null, List.of("RAG是什么")));
        assertEquals(1, response.vectors().size());
        assertEquals(1024, response.vectors().get(0).size());
        System.out.println("EMBEDDING_REAL: dim=" + response.vectors().get(0).size());
    }

    @Test
    void rerank_realBgeRerankerCall() {
        RerankResponse response = rerankClient.rerank(new RerankRequest(
                "siliconflow",
                null,
                "RAG是什么",
                List.of("检索增强生成（RAG）", "数据库分库分表方案"),
                2));
        assertEquals(2, response.results().size());
        System.out.println("RERANK_REAL: " + response.results());
    }
}
