package com.rag2agent.bootstrap.dto;

import com.rag2agent.bootstrap.entity.KnowledgeBase;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

public final class KbDtos {

    private KbDtos() {}

    public record CreateKnowledgeBaseRequest(
            @NotBlank(message = "知识库名称不能为空") String name,
            String description,
            String visibility) {}

    public record KnowledgeBaseView(
            Long id,
            String name,
            String description,
            Long tenantId,
            Long orgId,
            String visibility,
            Long ownerUserId,
            String accessRole,
            Instant createdAt) {

        public static KnowledgeBaseView from(KnowledgeBase kb) {
            return new KnowledgeBaseView(
                    kb.getId(), kb.getName(), kb.getDescription(),
                    kb.getTenantId(), kb.getOrgId(), kb.getVisibility(),
                    kb.getOwnerUserId(), kb.getAccessRole(), kb.getCreatedAt());
        }
    }

    public record GrantKnowledgeBaseMemberRequest(
            Long userId,
            String username,
            @NotBlank(message = "role 不能为空") String role) {}

    public record UpdateKnowledgeBaseVisibilityRequest(
            @NotBlank(message = "visibility 不能为空") String visibility) {}

    public record KnowledgeBaseMemberView(
            Long userId,
            String username,
            String nickname,
            String role,
            Instant createdAt) {}
}
