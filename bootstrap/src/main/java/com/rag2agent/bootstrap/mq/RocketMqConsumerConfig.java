package com.rag2agent.bootstrap.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag2agent.bootstrap.service.IngestPipelineService;
import java.nio.charset.StandardCharsets;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 消费者配置
 * @author 21311
 */
@Configuration
public class RocketMqConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(RocketMqConsumerConfig.class);

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer ingestConsumer(
            @Value("${ROCKETMQ_NAMESRV:localhost:19876}") String namesrvAddr,
            IngestPipelineService pipelineService,
            ObservationRegistry observationRegistry,
            ObjectMapper objectMapper) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("rag2agent-ingest-consumer");
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.subscribe(RocketMqProducerConfig.INGEST_TOPIC, "*"); //订阅此生产者的消息
        consumer.setConsumeThreadMin(2);
        consumer.setConsumeThreadMax(4);
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (MessageExt message : messages) {
                try {
                    IngestTaskMessage taskMessage = parseMessage(message.getBody(), objectMapper);
                    long documentId = taskMessage.documentId();
                    String traceId = message.getUserProperty("traceId");
                    Observation observation = Observation.createNotStarted(
                                    "rag2agent.mq.consumer", observationRegistry)
                            .lowCardinalityKeyValue("topic", message.getTopic())
                            .highCardinalityKeyValue("messaging.trace_id", traceId == null ? "" : traceId);
                    observation.observe(() -> pipelineService.process(documentId, taskMessage.taskId()));
                    log.info("入库任务消费完成: documentId={}, traceId={}", documentId, traceId);
                } catch (Exception e) {
                    log.error("入库消费失败，稍后重试: documentId={}, traceId={}",
                            new String(message.getBody(), StandardCharsets.UTF_8),
                            message.getUserProperty("traceId"), e);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER; //消费失败，按退避间隔重新投送，间隔是递增的，16 次都没成功，消息会被投进死信队列
                    //按批回调信息会导致整批消息重新投递，需要确保幂等性
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        return consumer;
    }

    private IngestTaskMessage parseMessage(byte[] body, ObjectMapper objectMapper) throws Exception {
        String payload = new String(body, StandardCharsets.UTF_8);
        if (payload.trim().startsWith("{")) {
            IngestTaskMessage message = objectMapper.readValue(payload, IngestTaskMessage.class);
            if (message.documentId() == null || message.documentId() <= 0) {
                throw new IllegalArgumentException("入库消息 documentId 无效");
            }
            return message;
        }
        // 兼容升级前已经在队列中的纯 documentId 消息。
        long documentId = Long.parseLong(payload);
        if (documentId <= 0) {
            throw new IllegalArgumentException("入库消息 documentId 无效");
        }
        return new IngestTaskMessage(documentId, null);
    }
}
