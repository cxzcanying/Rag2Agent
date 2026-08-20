package com.rag2agent.bootstrap.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AgentDtos {

    private AgentDtos() {}

    public record ChatRequest(
            @NotNull(message = "kbId 不能为空") Long kbId,
            @NotBlank(message = "query 不能为空") @Size(max = 100000, message = "query 不能超过 100000 个字符") String query,
            String sessionId) {}

    public record ApprovalRequest(boolean approved) {}
}
