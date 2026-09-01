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

/**
 * 幂等服务 - 为有副作用的API请求提供幂等性保证
 *
 * 核心机制：
 * 1. 基于 userId + scope + idempotencyKey 构建唯一索引
 * 2. 存储请求参数的SHA-256指纹，防止同一Key被用于不同请求
 * 3. 记录响应结果，用于缓存重放
 * 4. 24小时自动过期清理
 *
 * @author 21311
 */
@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 规范化幂等键
     *
     * 功能：
     * 1. 去除首尾空白字符
     * 2. 长度校验（不超过128字符）
     * 3. 空值或纯空白返回null（表示不需要幂等）
     *
     * @param key 原始幂等键（通常来自HTTP头 Idempotency-Key）
     * @return 规范化后的键，或null（如果无效）
     */
    public String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String value = key.trim();
        if (value.length() > 128) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Idempotency-Key 不能超过 128 个字符");
        }
        return value;
    }

    /**
     * 预留幂等记录（核心方法 - 第一阶段）
     * <p>
     * 执行流程：
     * 1. 清理24小时前的过期记录
     * 2. 尝试插入新记录（状态：response_json为NULL，表示处理中）
     * 3. 如果插入成功（无重复键），返回null，允许执行业务逻辑
     * 4. 如果插入失败（DuplicateKeyException）：
     *    a. 查询已有记录
     *    b. 比较请求指纹，如果不一致 -> 拒绝（Key已被占用）
     *    c. 如果指纹一致且response_json为null -> 说明正在处理中，提示重试
     *    d. 如果指纹一致且response_json有值 -> 直接返回缓存的响应（幂等重放）
     * <p>
     * 这是典型的"先占坑，后执行"模式，确保并发请求不会重复执行
     *
     * @param userId 用户ID（隔离不同用户的幂等空间）
     * @param scope 业务范围（如："agent_chat", "payment"等）
     * @param key 幂等键
     * @param request 请求对象（用于生成指纹）
     * @return null表示允许执行，String表示缓存的响应（直接返回给客户端）
     */
    public String reserve(Long userId, String scope, String key, Object request) {
        String normalized = normalizeKey(key);
        if (normalized == null) {
            return null;
        }

        // 生成请求参数指纹（SHA-256）
        String hash = hash(request);

        // 清理过期记录（24小时前），防止表无限膨胀
        jdbc.update("DELETE FROM idempotency_record WHERE owner_user_id=? AND scope=? AND idempotency_key=? AND created_at < now() - interval '24 hours'",
                userId, scope, normalized);

        try {
            // 尝试插入记录，response_json初始为NULL表示"处理中"
            jdbc.update("INSERT INTO idempotency_record(owner_user_id, scope, idempotency_key, request_hash) VALUES (?, ?, ?, ?)",
                    userId, scope, normalized, hash);
            // 插入成功，返回null，业务层可以继续执行
            return null;
        } catch (DuplicateKeyException ignored) {
            // 幂等键已存在，查询已有记录
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT request_hash, response_json::text AS response_json FROM idempotency_record WHERE owner_user_id=? AND scope=? AND idempotency_key=?",
                    userId, scope, normalized);

            // 检查请求指纹是否一致（防止用同一Key发不同请求）
            if (!hash.equals(row.get("request_hash"))) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Idempotency-Key 已用于其他请求");
            }

            String response = (String) row.get("response_json");
            if (response == null) {
                // response_json为NULL说明另一个请求正在处理中
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "相同请求正在处理中，请稍后重试");
            }

            // 已有完整响应，直接返回（幂等重放）
            return response;
        }
    }

    /**
     * 完成幂等记录（第二阶段）
     * <p>
     * 在业务逻辑成功执行后调用，将响应结果更新到记录中
     * 后续相同请求可以直接返回此缓存的响应
     *
     * @param userId 用户ID
     * @param scope 业务范围
     * @param key 幂等键
     * @param response 响应对象（会被序列化为JSONB存入数据库）
     */
    public void complete(Long userId, String scope, String key, Object response) {
        String normalized = normalizeKey(key);
        if (normalized == null) {
            return;
        }

        try {
            // 将响应对象序列化为JSON，更新到数据库
            jdbc.update("UPDATE idempotency_record SET response_json = CAST(? AS jsonb) WHERE owner_user_id=? AND scope=? AND idempotency_key=?",
                    objectMapper.writeValueAsString(response), userId, scope, normalized);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("幂等响应序列化失败", exception);
        }
    }

    /**
     * 释放幂等记录（异常回滚:业务异常或系统异常）
     * <p>
     * 当业务执行失败（抛异常）时调用，删除记录，释放幂等键
     * 注意：只删除response_json为NULL的记录（即处理中的记录）
     * 如果已有响应（complete已调用），则不会删除，保留用于重放
     *
     * @param userId 用户ID
     * @param scope 业务范围
     * @param key 幂等键
     */
    public void release(Long userId, String scope, String key) {
        String normalized = normalizeKey(key);
        if (normalized != null) {
            // 只删除未完成的记录（response_json为NULL）
            jdbc.update("DELETE FROM idempotency_record WHERE owner_user_id=? AND scope=? AND idempotency_key=? AND response_json IS NULL",
                    userId, scope, normalized);
        }
    }

    /**
     * 生成请求参数的SHA-256指纹
     * <p>
     * 用于检测同一幂等键是否被用于不同参数的请求
     * 将请求对象序列化为JSON后计算哈希
     *
     * @param request 请求对象
     * @return 32字节的十六进制字符串
     */
    private String hash(Object request) {
        try {
            // 将请求对象序列化为JSON字符串
            byte[] bytes = objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            // 计算SHA-256哈希，转换为十六进制字符串
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("幂等请求指纹生成失败", exception);
        }
    }
}