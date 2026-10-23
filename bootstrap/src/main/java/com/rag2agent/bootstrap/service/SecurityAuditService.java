package com.rag2agent.bootstrap.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 安全审计只记录主体、动作和结果，不记录正文、token 或密钥。 */
@Service
public class SecurityAuditService {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SecurityAuditService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void allow(
            Long tenantId,
            Long actorUserId,
            String action,
            String resourceType,
            Long resourceId,
            Map<String, ?> details) {
        record(tenantId, actorUserId, action, resourceType, resourceId, "ALLOW", null, details);
    }

    public void deny(
            Long tenantId,
            Long actorUserId,
            String action,
            String resourceType,
            Long resourceId,
            String reason,
            Map<String, ?> details) {
        record(tenantId, actorUserId, action, resourceType, resourceId, "DENY", reason, details);
    }

    private void record(
            Long tenantId,
            Long actorUserId,
            String action,
            String resourceType,
            Long resourceId,
            String outcome,
            String reason,
            Map<String, ?> details) {
        try {
            jdbc.update("""
                    INSERT INTO security_audit
                        (tenant_id, actor_user_id, action, resource_type, resource_id,
                         outcome, reason, details, trace_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                    """,
                    tenantId,
                    actorUserId,
                    action,
                    resourceType,
                    resourceId,
                    outcome,
                    reason,
                    json(details),
                    MDC.get("traceId"));
        } catch (RuntimeException exception) {
            // 审计故障不能把拒绝变成放行，也不能掩盖原始业务错误。
            log.error("安全审计写入失败: action={}, outcome={}", action, outcome, exception);
        }
    }

    private String json(Map<String, ?> details) {
        try {
            return objectMapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }
}
