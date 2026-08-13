package com.rag2agent.bootstrap.service;

import com.rag2agent.bootstrap.mq.RocketMqProducerConfig;
import com.rag2agent.framework.common.ErrorCode;
import com.rag2agent.framework.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IngestMessageService {

    private static final Logger log = LoggerFactory.getLogger(IngestMessageService.class);

    private final DefaultMQProducer producer;

    public IngestMessageService(DefaultMQProducer producer) {
        this.producer = producer;
    }

    public void sendIngestTask(Long documentId) {
        try {
            Message message = new Message(
                    RocketMqProducerConfig.INGEST_TOPIC,
                    String.valueOf(documentId).getBytes(StandardCharsets.UTF_8));
            producer.send(message);
            log.info("入库任务消息已发送: documentId={}", documentId);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "发送入库任务失败: " + e.getMessage());
        }
    }
}
