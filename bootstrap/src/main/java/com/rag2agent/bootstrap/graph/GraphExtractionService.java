package com.rag2agent.bootstrap.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.graph.KnowledgeGraphRepository.GraphEntity;
import com.rag2agent.bootstrap.graph.KnowledgeGraphRepository.GraphRelation;
import com.rag2agent.infra.ai.client.ChatModelClient;
import com.rag2agent.infra.ai.model.ChatCompletionRequest;
import com.rag2agent.infra.ai.model.ChatCompletionResponse;
import com.rag2agent.infra.ai.model.ChatMessage;
import com.rag2agent.rag.core.document.StructuredDocument;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 用一个结构化 LLM 调用抽取实体和关系，并把结果限制为文档内可回链元素。 */
@Service
public class GraphExtractionService {

    private static final int MAX_ELEMENT_CHARS = 800;
    private static final int MAX_DOCUMENT_CHARS = 12000;

    private final ChatModelClient chatClient;
    private final ObjectMapper objectMapper;
    private com.rag2agent.bootstrap.service.TokenCostService tokenCostService;

    public GraphExtractionService(ChatModelClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTokenCostService(com.rag2agent.bootstrap.service.TokenCostService tokenCostService) {
        this.tokenCostService = tokenCostService;
    }

    public ExtractionResult extract(StructuredDocument document) {
        List<StructuredDocument.Element> elements = document.elements().stream()
                .filter(element -> element.text() != null && !element.text().isBlank())
                .toList();
        Map<String, String> elementText = new HashMap<>();
        StringBuilder source = new StringBuilder();
        for (StructuredDocument.Element element : elements) {
            String text = element.text().trim();
            if (source.length() + text.length() > MAX_DOCUMENT_CHARS) {
                break;
            }
            elementText.put(element.id(), text);
            source.append("[").append(element.id()).append("][")
                    .append(element.type()).append("] ")
                    .append(text.length() > MAX_ELEMENT_CHARS
                            ? text.substring(0, MAX_ELEMENT_CHARS)
                            : text)
                    .append('\n');
        }
        if (elementText.isEmpty()) {
            return new ExtractionResult(List.of(), List.of());
        }
        java.time.Instant startedAt = java.time.Instant.now();
        ChatCompletionResponse response = chatClient.complete(new ChatCompletionRequest(
                chatClient.providerName(),
                null,
                List.of(
                        new ChatMessage("system", """
                                你是企业知识图谱抽取器。只输出 JSON 对象，不输出 Markdown。
                                JSON 格式：
                                {"entities":[{"name":"规范实体名","type":"实体类型","evidence":"元素ID","confidence":0.0}],
                                 "relations":[{"source":"实体名","target":"实体名","type":"关系类型","evidence":"元素ID","confidence":0.0}]}
                                实体名使用原文语言；未知类型使用 general；证据必须是输入中出现过的元素 ID。
                                只抽取文本明确支持的事实，不推断隐含关系。
                                """),
                        new ChatMessage("user", source.toString())),
                Map.of("max_tokens", 2048, "temperature", 0.0)));
        if (tokenCostService != null) {
            tokenCostService.record(
                    com.rag2agent.bootstrap.service.TokenCostService.CostContext.current(
                            "graph.extraction", startedAt),
                    response.usage(), null, List.of(),
                    chatClient.providerName(), chatClient.modelName());
        }
        return parse(response.content(), elementText);
    }

    ExtractionResult parse(String content, Map<String, String> elementText) {
        try {
            String json = stripFence(content);
            JsonNode root = objectMapper.readTree(json);
            List<GraphEntity> entities = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            for (JsonNode item : root.path("entities")) {
                String name = item.path("name").asText("").trim();
                String evidence = item.path("evidence").asText("");
                if (name.isBlank() || !elementText.containsKey(evidence)) {
                    continue;
                }
                names.add(name);
                entities.add(new GraphEntity(
                        name,
                        item.path("type").asText("general"),
                        evidence,
                        item.path("confidence").asDouble(0.6)));
            }
            List<GraphRelation> relations = new ArrayList<>();
            for (JsonNode item : root.path("relations")) {
                String source = item.path("source").asText("").trim();
                String target = item.path("target").asText("").trim();
                String evidence = item.path("evidence").asText("");
                if (!names.contains(source) || !names.contains(target)
                        || !elementText.containsKey(evidence)) {
                    continue;
                }
                relations.add(new GraphRelation(
                        source,
                        target,
                        item.path("type").asText("related_to"),
                        evidence,
                        item.path("confidence").asDouble(0.5)));
            }
            return new ExtractionResult(entities, relations);
        } catch (Exception exception) {
            throw new IllegalArgumentException("图谱抽取 JSON 无效", exception);
        }
    }

    private String stripFence(String content) {
        if (content == null) {
            return "";
        }
        String value = content.trim();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                value = value.substring(firstLine + 1, lastFence).trim();
            }
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : value;
    }

    public record ExtractionResult(
            List<GraphEntity> entities, List<GraphRelation> relations) {
        public ExtractionResult {
            entities = List.copyOf(entities);
            relations = List.copyOf(relations);
        }
    }
}
