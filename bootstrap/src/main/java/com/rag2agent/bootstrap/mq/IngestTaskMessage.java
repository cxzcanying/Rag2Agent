package com.rag2agent.bootstrap.mq;

/** 入库消息的业务主键，避免消费端按“最新任务”误取其他任务。 */
public record IngestTaskMessage(Long documentId, Long taskId) {}
