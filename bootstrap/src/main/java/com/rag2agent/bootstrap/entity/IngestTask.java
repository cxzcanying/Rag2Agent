package com.rag2agent.bootstrap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

@Data
@TableName("ingest_task")
public class IngestTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private String status;
    private String currentStage;
    private Integer retryCount;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
}
