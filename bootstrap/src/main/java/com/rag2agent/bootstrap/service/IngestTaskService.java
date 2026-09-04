package com.rag2agent.bootstrap.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.rag2agent.bootstrap.entity.IngestTask;
import com.rag2agent.bootstrap.mapper.IngestTaskMapper;
import java.util.List;
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

    public IngestTask findById(Long taskId) {
        return taskId == null || taskId <= 0 ? null : taskMapper.selectById(taskId);
    }

    public void markStage(Long taskId, String stage) {
        taskMapper.update(null, new LambdaUpdateWrapper<IngestTask>()
                .eq(IngestTask::getId, taskId)
                .ne(IngestTask::getStatus, "INDEXED")
                .set(IngestTask::getCurrentStage, stage)
                .set(IngestTask::getStatus, stage));
    }

    //失败终态
    public void markFailed(Long taskId, String errorMessage) {
        taskMapper.update(null, new LambdaUpdateWrapper<IngestTask>()
                .eq(IngestTask::getId, taskId)
                .ne(IngestTask::getStatus, "INDEXED")
                .set(IngestTask::getStatus, "FAILED")
                .set(IngestTask::getErrorMessage, truncate(errorMessage)));
    }

    //成功终态
    public void markIndexed(Long taskId) {
        taskMapper.update(null, new LambdaUpdateWrapper<IngestTask>()
                .eq(IngestTask::getId, taskId)
                .ne(IngestTask::getStatus, "INDEXED")
                .set(IngestTask::getStatus, "INDEXED")
                .set(IngestTask::getCurrentStage, "INDEXED")
                .set(IngestTask::getErrorMessage, null));
    }

    // 手动重试：重置任务为 PENDING，供 reingest 使用
    public void resetToPending(Long taskId) {
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<IngestTask>()
                .eq(IngestTask::getId, taskId)
                .eq(IngestTask::getStatus, "FAILED")
                .set(IngestTask::getStatus, "PENDING")
                .set(IngestTask::getCurrentStage, "PENDING")
                .set(IngestTask::getErrorMessage, null));
        if (updated != 1) {
            throw new IllegalStateException("任务状态已变化，无法重置为 PENDING: " + taskId);
        }
    }

    /** 中断状态集合：应用崩溃时任务会停在非终态，用于启动/周期恢复。 */
    private static final List<String> INTERRUPTED_STATES =
            List.of("PENDING", "PARSING", "SPLITTING", "EMBEDDING");

    /** 列出所有处于中间态（可能因崩溃滞留）的入库任务，启动恢复用。 */
    public List<IngestTask> listInterrupted() {
        return taskMapper.selectList(new LambdaQueryWrapper<IngestTask>()
                .in(IngestTask::getStatus, INTERRUPTED_STATES)
                .orderByAsc(IngestTask::getId));
    }

    /** 列出卡在中间态且超过 staleSeconds 未推进的入库任务，周期恢复用。 */
    public List<IngestTask> listInterruptedStale(long staleSeconds) {
        return taskMapper.selectList(new LambdaQueryWrapper<IngestTask>()
                .in(IngestTask::getStatus, INTERRUPTED_STATES)
                .apply("updated_at < now() - make_interval(secs => {0})", staleSeconds)
                .orderByAsc(IngestTask::getId));
    }

    /**
     * 把中断任务重置为 PENDING，供恢复扫描重新驱动。
     * 只影响中间态（非 INDEXED/FAILED），避免覆盖已成功或已终态的任务。
     * @return 受影响行数，0 表示任务已处于终态
     */
    public int requeueInterrupted(Long taskId) {
        return taskMapper.update(null, new LambdaUpdateWrapper<IngestTask>()
                .eq(IngestTask::getId, taskId)
                .in(IngestTask::getStatus, INTERRUPTED_STATES)
                .set(IngestTask::getStatus, "PENDING")
                .set(IngestTask::getCurrentStage, "PENDING")
                .set(IngestTask::getErrorMessage, null));
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 2000) {
            return message;
        }
        return message.substring(0, 2000);
    }
}
