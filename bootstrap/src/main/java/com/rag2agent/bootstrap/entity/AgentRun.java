package com.rag2agent.bootstrap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

@Data
@TableName("agent_run")
public class AgentRun {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String clientRequestId;
    private Long userId;
    private String status;
    private String query;
    private String answer;
    private Integer maxIterations;
    private Instant createdAt;
    private Instant updatedAt;
}
