package com.rag2agent.bootstrap.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 有副作用 API 的幂等记录，参数指纹不一致时拒绝复用。 */
@Service
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public String normalizeKey(String key) {
        if (key == null || key.isBlank()) return null;
        String value = key.trim();
        if (value.length() > 128) throw new BusinessException(ErrorCode.BAD_REQUEST, "Idempotency-Key 不能超过 128 个字符");
        return value;
    }

    public String reserve(Long userId, String scope, String key, Object request) {
        String normalized = normalizeKey(key);
        if (normalized == null) return null;
        String hash = hash(request);
        jdbc.update("DELETE FROM idempotency_record WHERE owner_user_id=? AND scope=? AND idempotency_key=? AND created_at < now() - interval '24 hours'",
                userId, scope, normalized);
        try {
            jdbc.update("INSERT INTO idempotency_record(owner_user_id, scope, idempotency_key, request_hash) VALUES (?, ?, ?, ?)",
                    userId, scope, normalized, hash);
            return null;
        } catch (DuplicateKeyException ignored) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT request_hash, response_json::text AS response_json FROM idempotency_record WHERE owner_user_id=? AND scope=? AND idempotency_key=?",
                    userId, scope, normalized);
            if (!hash.equals(row.get("request_hash"))) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Idempotency-Key 已用于其他请求");
            }
            String response = (String) row.get("response_json");
            if (response == null) {
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "相同请求正在处理中，请稍后重试");
            }
            return response;
        }
    }

    public void complete(Long userId, String scope, String key, Object response) {
        String normalized = normalizeKey(key);
        if (normalized == null) return;
        try {
            jdbc.update("UPDATE idempotency_record SET response_json = CAST(? AS jsonb) WHERE owner_user_id=? AND scope=? AND idempotency_key=?",
                    objectMapper.writeValueAsString(response), userId, scope, normalized);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("幂等响应序列化失败", exception);
        }
    }

    public void release(Long userId, String scope, String key) {
        String normalized = normalizeKey(key);
        if (normalized != null) {
            jdbc.update("DELETE FROM idempotency_record WHERE owner_user_id=? AND scope=? AND idempotency_key=? AND response_json IS NULL",
                    userId, scope, normalized);
        }
    }

    private String hash(Object request) {
        try {
            byte[] bytes = objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("幂等请求指纹生成失败", exception);
        }
    }
}
