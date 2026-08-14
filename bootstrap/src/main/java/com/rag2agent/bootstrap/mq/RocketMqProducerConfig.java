package com.rag2agent.bootstrap.mq;

import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 生产者配置
 * @author 21311
 */
@Configuration
public class RocketMqProducerConfig {

    public static final String INGEST_TOPIC = "INGEST_TOPIC";

    @Bean(destroyMethod = "shutdown")
    public DefaultMQProducer rocketMqProducer(
            @Value("${ROCKETMQ_NAMESRV:localhost:9876}") String namesrvAddr) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("rag2agent-ingest-producer");
        producer.setNamesrvAddr(namesrvAddr);
        producer.setSendMsgTimeout(3000);
        producer.start();
        return producer;
    }
}
