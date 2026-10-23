package com.rag2agent.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 不同阻塞资源使用独立有界队列，避免一个慢依赖占满全部执行槽。 */
@ConfigurationProperties(prefix = "rag2agent.concurrency")
public class ConcurrencyProperties {

    private Pool evaluation = new Pool(1, 1, 100);
    private Pool retrieval = new Pool(4, 4, 32);
    private Pool tool = new Pool(4, 4, 32);
    private Pool agentTeam = new Pool(2, 2, 32);
    private Pool agentWorker = new Pool(2, 4, 32);
    private Pool memory = new Pool(1, 2, 32);
    private Pool ingest = new Pool(2, 2, 32);
    private Pool docling = new Pool(1, 2, 16);

    public Pool getEvaluation() {
        return evaluation;
    }

    public void setEvaluation(Pool evaluation) {
        this.evaluation = evaluation;
    }

    public Pool getRetrieval() {
        return retrieval;
    }

    public void setRetrieval(Pool retrieval) {
        this.retrieval = retrieval;
    }

    public Pool getTool() {
        return tool;
    }

    public void setTool(Pool tool) {
        this.tool = tool;
    }

    public Pool getAgentTeam() {
        return agentTeam;
    }

    public void setAgentTeam(Pool agentTeam) {
        this.agentTeam = agentTeam;
    }

    public Pool getAgentWorker() {
        return agentWorker;
    }

    public void setAgentWorker(Pool agentWorker) {
        this.agentWorker = agentWorker;
    }

    public Pool getMemory() {
        return memory;
    }

    public void setMemory(Pool memory) {
        this.memory = memory;
    }

    public Pool getIngest() {
        return ingest;
    }

    public void setIngest(Pool ingest) {
        this.ingest = ingest;
    }

    public Pool getDocling() {
        return docling;
    }

    public void setDocling(Pool docling) {
        this.docling = docling;
    }

    public static class Pool {

        private int coreSize;
        private int maxSize;
        private int queueCapacity;

        public Pool() {}

        public Pool(int coreSize, int maxSize, int queueCapacity) {
            setCoreSize(coreSize);
            setMaxSize(maxSize);
            setQueueCapacity(queueCapacity);
        }

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = Math.max(1, coreSize);
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = Math.max(coreSize, maxSize);
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = Math.max(1, queueCapacity);
        }
    }
}
