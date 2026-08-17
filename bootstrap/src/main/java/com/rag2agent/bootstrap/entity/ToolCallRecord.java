package com.rag2agent.bootstrap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/**
 * 工具调用记录（含人工审批状态）。input/output 在库里是 JSONB，Java 侧存 JSON 字符串。
 */
@Data
@TableName("tool_call")
public class ToolCallRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long stepId;
    private String toolName;
    private String status;
    private String input;
    private String output;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
}
