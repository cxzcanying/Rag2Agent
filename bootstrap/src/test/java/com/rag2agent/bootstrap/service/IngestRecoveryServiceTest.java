package com.rag2agent.bootstrap.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rag2agent.bootstrap.config.RecoveryProperties;
import com.rag2agent.bootstrap.entity.IngestTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class IngestRecoveryServiceTest {

    @Test
    void redrivesInterruptedTaskWhenDocumentUnlocked() {
        IngestTask task = new IngestTask();
        task.setId(11L);
        task.setDocumentId(4L);
        task.setStatus("PENDING");
        IngestTaskService tasks = mock(IngestTaskService.class);
        when(tasks.listInterruptedStale(1800)).thenReturn(List.of(task));
        when(tasks.requeueInterrupted(11L)).thenReturn(1);
        IngestMessageService messages = mock(IngestMessageService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey("rag2agent:ingest:lock:4")).thenReturn(false);

        IngestRecoveryService service = new IngestRecoveryService(
                tasks, messages, redis, new RecoveryProperties(), new SimpleMeterRegistry());

        service.recoverStale();

        verify(tasks).listInterruptedStale(1800);
        verify(redis).hasKey("rag2agent:ingest:lock:4");
        verify(tasks).requeueInterrupted(11L);
        verify(messages).sendIngestTask(4L, 11L);
    }

    @Test
    void skipsWhenDocumentLocked() {
        IngestTask task = new IngestTask();
        task.setId(12L);
        task.setDocumentId(5L);
        task.setStatus("EMBEDDING");
        IngestTaskService tasks = mock(IngestTaskService.class);
        when(tasks.listInterruptedStale(1800)).thenReturn(List.of(task));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey("rag2agent:ingest:lock:5")).thenReturn(true);

        IngestRecoveryService service = new IngestRecoveryService(
                tasks, mock(IngestMessageService.class), redis, new RecoveryProperties(),
                new SimpleMeterRegistry());

        service.recoverStale();

        verify(tasks, never()).requeueInterrupted(anyLong());
    }

    @Test
    void doesNothingWhenDisabled() {
        RecoveryProperties recovery = new RecoveryProperties();
        recovery.setEnabled(false);
        IngestTaskService tasks = mock(IngestTaskService.class);
        IngestRecoveryService service = new IngestRecoveryService(
                tasks, mock(IngestMessageService.class), mock(StringRedisTemplate.class),
                recovery, new SimpleMeterRegistry());

        service.recoverStale();

        verify(tasks, never()).listInterruptedStale(anyLong());
    }
}
