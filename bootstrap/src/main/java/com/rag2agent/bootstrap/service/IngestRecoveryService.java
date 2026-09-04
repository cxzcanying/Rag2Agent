package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.config.RecoveryProperties;
import com.rag2agent.bootstrap.entity.IngestTask;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 异步入库中断任务恢复：应用在"任务已落库但消息未发送"或"处理中途崩溃"时会留下
 * PENDING/PARSING/SPLITTING/EMBEDDING 的非终态任务，这里在启动时和周期扫描时重新驱动，
 * 作为 RocketMQ 至少一次投递与手动 reingest 之外的兜底。
 * <p>
 * 只处理非终态任务；INDEXED/FAILED 不自动重试（FAILED 保留给 manual reingest）。
 * 每个文档都受 {@code rag2agent:ingest:lock:{documentId}} 保护，扫描前先检查锁，
 * 避免在多实例场景重复处理。
 * @author 21311
 */
@Component
public class IngestRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(IngestRecoveryService.class);
    private static final String LOCK_PREFIX = "rag2agent:ingest:lock:";

    private final IngestTaskService ingestTaskService;
    private final IngestMessageService ingestMessageService;
    private final StringRedisTemplate redis;
    private final RecoveryProperties recoveryProperties;
    private final MeterRegistry meterRegistry;

    public IngestRecoveryService(
            IngestTaskService ingestTaskService,
            IngestMessageService ingestMessageService,
            StringRedisTemplate redis,
            RecoveryProperties recoveryProperties,
            MeterRegistry meterRegistry) {
        this.ingestTaskService = ingestTaskService;
        this.ingestMessageService = ingestMessageService;
        this.redis = redis;
        this.recoveryProperties = recoveryProperties;
        this.meterRegistry = meterRegistry;
    }

    /** 启动即恢复：崩溃遗留的所有中间态任务无条件重驱（此时无其他实例持有锁）。 */
    @PostConstruct
    void recoverOnStartup() {
        // 放后台线程执行，避免同步 pipeline 阻塞上下文初始化；daemon 不阻止 JVM 退出。
        Thread startup = new Thread(
                () -> recover(ingestTaskService.listInterrupted()), "ingest-recovery-startup");
        startup.setDaemon(true);
        startup.start();
    }

    /** 周期扫描：只重驱卡在中间态超过阈值、且当前未被锁持有文档。 */
    @Scheduled(initialDelay = 10000, fixedDelayString = "${rag2agent.recovery.scan-interval-ms:60000}")
    public void recoverStale() {
        if (!recoveryProperties.isEnabled()) {
            return;
        }
        recover(ingestTaskService.listInterruptedStale(recoveryProperties.getIngestStaleSeconds()));
    }

    private void recover(List<IngestTask> tasks) {
        for (IngestTask task : tasks) {
            redrive(task);
        }
    }

    private void redrive(IngestTask task) {
        String lockKey = LOCK_PREFIX + task.getDocumentId();
        if (Boolean.TRUE.equals(redis.hasKey(lockKey))) {
            metric("skipped_locked");
            return;
        }
        // 只有非终态才能重置成功；若已被处理成 INDEXED/FAILED，返回 0 直接跳过。
        if (ingestTaskService.requeueInterrupted(task.getId()) != 1) {
            metric("skipped_terminal");
            return;
        }
        try {
            ingestMessageService.sendIngestTask(task.getDocumentId(), task.getId());
            metric("recovered");
            log.info("入库中断任务已重新驱动: taskId={}, documentId={}", task.getId(), task.getDocumentId());
        } catch (RuntimeException exception) {
            // 无 MQ 时 sendIngestTask 会同步跑 pipeline，若恰好被另一实例抢占会抛"正在入库"，
            // 这是正常争锁而非失败，不回写 FAILED；其余异常才按失败处理，避免留下永不处理的 PENDING。
            if (exception.getMessage() != null && exception.getMessage().contains("正在入库")) {
                metric("skipped_locked");
                return;
            }
            ingestTaskService.markFailed(task.getId(), "恢复重驱失败: " + exception.getMessage());
            metric("failed");
            log.warn("入库中断任务重驱失败: taskId={}, documentId={}",
                    task.getId(), task.getDocumentId(), exception);
        }
    }

    private void metric(String outcome) {
        meterRegistry.counter("rag2agent.ingest.recovery", "outcome", outcome).increment();
    }
}
