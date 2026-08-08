package com.rag2agent.bootstrap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

@Data
@TableName("document")
public class DocumentMeta {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long kbId;
    private String fileName;
    private String fileType;
    private String storagePath;
    private Long fileSize;
    private Integer version;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
