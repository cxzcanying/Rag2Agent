package com.rag2agent.bootstrap.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag2agent.bootstrap.entity.IngestTask;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface IngestTaskMapper extends BaseMapper<IngestTask> {

    /**
     * 删除某文档的所有入库任务，供 delete_document 工具使用（外键约束要求先删任务再删文档）。
     */
    @Delete("DELETE FROM ingest_task WHERE document_id = #{documentId}")
    int deleteByDocument(@Param("documentId") Long documentId);
}
