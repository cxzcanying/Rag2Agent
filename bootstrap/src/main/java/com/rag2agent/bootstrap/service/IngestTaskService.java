package com.rag2agent.bootstrap.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.rag2agent.bootstrap.entity.IngestTask;
import com.rag2agent.bootstrap.mapper.IngestTaskMapper;
import org.springframework.stereotype.Service;

/**
 * 入库任务状态机：
 * PENDING -> PARSING -> SPLITTING -> EMBEDDING -> INDEXED / FAILED
 * @author 21311
 */
@Service
public class IngestTaskService {

    private final IngestTaskMapper taskMapper;

    public IngestTaskService(IngestTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    public Long create(Long documentId) {
        IngestTask task = new IngestTask();
        task.setDocumentId(documentId);
        task.setStatus("PENDING");
        task.setRetryCount(0);
        taskMapper.insert(task);
        return task.getId();
    }

    public IngestTask latestByDocument(Long documentId) {
        return taskMapper.selectOne(new LambdaQueryWrapper<IngestTask>()
                .eq(IngestTask::getDocumentId, documentId)
                .orderByDesc(IngestTask::getId)
                .last("LIMIT 1"));
    }

    public void markStage(Long taskId, String stage) {
        taskMapper.update(null, new LambdaUpdateWrapper<IngestTask>()
                .eq(IngestTask::getId, taskId)
                .set(IngestTask::getCurrentStage, stage)
                .set(IngestTask::getStatus, stage));
    }

    //失败终态
    public void markFailed(Long taskId, String errorMessage) {
        taskMapper.update(null, new LambdaUpdateWrapper<IngestTask>()
                .eq(IngestTask::getId, taskId)
                .set(IngestTask::getStatus, "FAILED")
                .set(IngestTask::getErrorMessage, truncate(errorMessage)));
    }

    //成功终态
    public void markIndexed(Long taskId) {
        taskMapper.update(null, new LambdaUpdateWrapper<IngestTask>()
                .eq(IngestTask::getId, taskId)
                .set(IngestTask::getStatus, "INDEXED")
                .set(IngestTask::getCurrentStage, "INDEXED")
                .set(IngestTask::getErrorMessage, null));
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 2000) {
            return message;
        }
        return message.substring(0, 2000);
    }
}
