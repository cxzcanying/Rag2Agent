package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.mq.RocketMqProducerConfig;
import com.rag2agent.bootstrap.mq.IngestTaskMessage;
import com.rag2agent.bootstrap.config.IngestProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import com.rag2agent.bootstrap.observability.MqTracePropagation;
import com.rag2agent.bootstrap.tenancy.TenantContext;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 状态机消息服务
 * @author 21311
 */
@Service
public class IngestMessageService {

    private static final Logger log = LoggerFactory.getLogger(IngestMessageService.class);

    private final DefaultMQProducer producer;
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;
    private final ObjectMapper objectMapper;
    private final IngestPipelineService pipelineService;
    private final ThreadPoolTaskExecutor ingestTaskExecutor;
    private final IngestProperties ingestProperties;
    private final boolean production;

    public IngestMessageService(
            ObjectProvider<DefaultMQProducer> producer,
            ObservationRegistry observationRegistry,
            Tracer tracer,
            ObjectMapper objectMapper,
            IngestPipelineService pipelineService,
            @Qualifier("ingestTaskExecutor") ThreadPoolTaskExecutor ingestTaskExecutor,
            IngestProperties ingestProperties,
            Environment environment) {
        this.producer = producer.getIfAvailable();
        this.observationRegistry = observationRegistry;
        this.tracer = tracer;
        this.objectMapper = objectMapper;
        this.pipelineService = pipelineService;
        this.ingestTaskExecutor = ingestTaskExecutor;
        this.ingestProperties = ingestProperties;
        this.production = environment.acceptsProfiles(Profiles.of("prod", "production"));
    }

    public void sendIngestTask(Long documentId) {
        sendIngestTask(documentId, null, TenantContext.requireTenantId());
    }

    public void sendIngestTask(Long documentId, Long taskId) {
        sendIngestTask(documentId, taskId, TenantContext.requireTenantId());
    }

    public void sendIngestTask(Long documentId, Long taskId, Long tenantId) {
        if (producer == null) {
            if (production || !ingestProperties.isLocalFallbackEnabled()) {
                throw new BusinessException(
                        ErrorCode.UPSTREAM_UNAVAILABLE, "正式环境要求启用消息队列入库");
            }
            try {
                ingestTaskExecutor.execute(() -> pipelineService.process(documentId, taskId));
            } catch (RuntimeException exception) {
                throw new BusinessException(
                        ErrorCode.RATE_LIMITED, "入库任务队列已满，请稍后重试");
            }
            return;
        }
        try {
            Span currentSpan = tracer.currentSpan();
            String traceId = currentSpan == null ? "" : currentSpan.context().traceId();
            Observation.createNotStarted("rag2agent.mq.producer", observationRegistry)
                    .lowCardinalityKeyValue("topic", RocketMqProducerConfig.INGEST_TOPIC)
                    .observe(() -> {
                        Message message = new Message(
                                RocketMqProducerConfig.INGEST_TOPIC, //使用TOPIC发布订阅模式
                                messageBodyUnchecked(documentId, taskId, tenantId)
                                        .getBytes(StandardCharsets.UTF_8));
                        if (!traceId.isBlank()) {
                            message.putUserProperty("traceId", traceId);
                        }
                        MqTracePropagation.inject(message);
                        try {
                            producer.send(message);
                        } catch (Exception e) {
                            throw new IllegalStateException("RocketMQ 消息发送失败", e);
                        }
                        return null;
                    });
            log.info("入库任务消息已发送: documentId={}, traceId={}", documentId, traceId);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "发送入库任务失败: " + e.getMessage());
        }
    }

    private String messageBody(Long documentId, Long taskId, Long tenantId)
            throws JsonProcessingException {
        return objectMapper.writeValueAsString(
                new IngestTaskMessage(documentId, taskId, tenantId));
    }

    private String messageBodyUnchecked(Long documentId, Long taskId, Long tenantId) {
        try {
            return messageBody(documentId, taskId, tenantId);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("入库消息序列化失败", e);
        }
    }
}
