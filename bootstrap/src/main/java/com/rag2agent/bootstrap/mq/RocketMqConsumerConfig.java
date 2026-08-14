package com.rag2agent.bootstrap.mq;

import com.rag2agent.bootstrap.service.IngestPipelineService;
import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RocketMqConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(RocketMqConsumerConfig.class);

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer ingestConsumer(
            @Value("${ROCKETMQ_NAMESRV:localhost:9876}") String namesrvAddr,
            IngestPipelineService pipelineService) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("rag2agent-ingest-consumer");
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.subscribe(RocketMqProducerConfig.INGEST_TOPIC, "*");
        consumer.setConsumeThreadMin(2);
        consumer.setConsumeThreadMax(4);
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (MessageExt message : messages) {
                try {
                    long documentId = Long.parseLong(
                            new String(message.getBody(), StandardCharsets.UTF_8));
                    pipelineService.process(documentId);
                } catch (Exception e) {
                    log.error("入库消费失败，稍后重试: {}", new String(message.getBody(), StandardCharsets.UTF_8), e);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        return consumer;
    }
}
