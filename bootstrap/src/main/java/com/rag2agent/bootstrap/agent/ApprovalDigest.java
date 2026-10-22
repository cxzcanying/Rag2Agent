package com.rag2agent.bootstrap.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** 规范化并摘要被审批的完整工具操作，供执行前重新校验。 */
public final class ApprovalDigest {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private ApprovalDigest() {}

    public static String compute(
            Long runId, Long userId, String toolName, String argumentsJson) {
        try {
            Object arguments = CANONICAL.readValue(argumentsJson, Object.class);
            String canonical = CANONICAL.writeValueAsString(Map.of(
                    "runId", runId,
                    "userId", userId,
                    "toolName", toolName,
                    "arguments", arguments));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalArgumentException("审批操作摘要计算失败", exception);
        }
    }

    public static boolean matches(String expected, String actual) {
        return expected != null
                && actual != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        actual.getBytes(StandardCharsets.US_ASCII));
    }
}
