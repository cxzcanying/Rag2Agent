package com.rag2agent.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rag2agent.bootstrap.service.IngestPipelineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 入库幂等验证（手动运行，不参与 CI）：
 * mvn -pl bootstrap -am test -Dtest=IngestPipelineIdempotentIT
 * 前置：数据库中已有 INDEXED 文档（本文档用 document_id=5 的 java基础讲义，511 chunks）。
 * 注意：强制重跑会真实调用一次 embedding，产生少量费用。
 */
@SpringBootTest
@ActiveProfiles("dev")
class IngestPipelineIdempotentIT {

    private static final long DOCUMENT_ID = 5L;

    @Autowired
    private IngestPipelineService pipelineService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void repeatedProcessDoesNotDuplicateChunks() {
        long beforeChunks = chunkCount();
        int beforeVersion = documentVersion();

        // 场景 1：任务已完成，消息被重复投递 -> 直接跳过，数据不变
        pipelineService.process(DOCUMENT_ID);
        assertEquals(beforeChunks, chunkCount(), "重复投递不应改变 chunk 数量");
        assertEquals(beforeVersion, documentVersion(), "跳过时版本不应变化");

        // 场景 2：上次处理到一半失败（任务 FAILED），消息重投 -> 重新入库
        jdbcTemplate.update(
                "UPDATE ingest_task SET status = 'FAILED' WHERE document_id = ? "
                        + "AND id = (SELECT max(id) FROM ingest_task WHERE document_id = ?)",
                DOCUMENT_ID, DOCUMENT_ID);
        pipelineService.process(DOCUMENT_ID);

        assertEquals(beforeChunks, chunkCount(), "重跑后 chunk 数量应保持不变（整体替换）");
        assertEquals(beforeVersion + 1, documentVersion(), "重跑后版本应 +1");
        Integer distinctVersions = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT version) FROM document_chunk WHERE document_id = ?",
                Integer.class, DOCUMENT_ID);
        assertEquals(1, distinctVersions, "旧版本 chunk 应被清理，只剩当前版本");
    }

    private long chunkCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ?", Long.class, DOCUMENT_ID);
        return count == null ? 0 : count;
    }

    private int documentVersion() {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM document WHERE id = ?", Integer.class, DOCUMENT_ID);
        return version == null ? 0 : version;
    }
}
