package com.rag2agent.bootstrap.dto;

import com.rag2agent.bootstrap.entity.KnowledgeBase;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

public final class KbDtos {

    private KbDtos() {}

    public record CreateKnowledgeBaseRequest(
            @NotBlank(message = "知识库名称不能为空") String name, String description) {}

    public record KnowledgeBaseView(Long id, String name, String description, Instant createdAt) {
        public static KnowledgeBaseView from(KnowledgeBase kb) {
            return new KnowledgeBaseView(kb.getId(), kb.getName(), kb.getDescription(), kb.getCreatedAt());
        }
    }
}
