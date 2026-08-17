package com.rag2agent.bootstrap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/**
 * Agent 执行步骤。input/output 在库里是 JSONB，Java 侧存 JSON 字符串。
 */
@Data
@TableName("agent_step")
public class AgentStep {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Integer seq;
    private String stepType;
    private String status;
    private String input;
    private String output;
    private Integer durationMs;
    private Instant createdAt;
}
