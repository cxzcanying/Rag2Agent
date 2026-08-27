package com.rag2agent.bootstrap.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.bootstrap.entity.IngestTask;
import com.rag2agent.bootstrap.mapper.DocumentChunkMapper;
import com.rag2agent.bootstrap.mapper.DocumentMetaMapper;
import com.rag2agent.bootstrap.storage.MinioStorageService;
import com.rag2agent.infra.ai.client.EmbeddingClient;
import com.rag2agent.infra.ai.model.EmbeddingRequest;
import com.rag2agent.infra.ai.model.EmbeddingResponse;
import com.rag2agent.rag.core.document.DocumentSource;
import com.rag2agent.rag.core.document.ParsedDocument;
import com.rag2agent.rag.core.document.impl.PdfBoxDocumentParser;
import com.rag2agent.rag.core.split.TextChunk;
import com.rag2agent.rag.core.split.impl.RecursiveTextSplitter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 入库流水线：下载原文 -> 解析 -> 切块 -> Embedding（分批）-> 写 pgvector -> 状态流转。
 *
 * <p>可靠性设计三件套：
 * <ul>
 *   <li>幂等：任务已 INDEXED 直接跳过；写入前先清理同版本残留，扛住 RocketMQ 消息重试；</li>
 *   <li>版本化：document.version 每次入库递增，chunk 带版本号，成功后删除旧版本数据，支持文档重新入库；</li>
 *   <li>事务：版本切换（更新文档版本 + 删除旧 chunk）在同一个事务内完成，避免出现"版本新、数据旧"的中间态。</li>
 * </ul>
 *
 * 任务状态统一由 {@link IngestTaskService} 管理；任一步失败抛异常，由消费者决定重试。
 * @author 21311
 */
@Service
public class IngestPipelineService {

    private static final Logger log = LoggerFactory.getLogger(IngestPipelineService.class);
    private static final int EMBEDDING_BATCH_SIZE = 16;
    private static final Duration INGEST_LOCK_TTL = Duration.ofHours(2);
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end";

    private final DocumentMetaMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final MinioStorageService storage;
    private final EmbeddingClient embeddingClient;
    private final IngestTaskService ingestTaskService;
    private final TransactionTemplate transactionTemplate;
    private final StringRedisTemplate redis;

    public IngestPipelineService(
            DocumentMetaMapper documentMapper,
            DocumentChunkMapper chunkMapper,
            MinioStorageService storage,
            EmbeddingClient embeddingClient,
            IngestTaskService ingestTaskService,
            PlatformTransactionManager transactionManager,
            StringRedisTemplate redis) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.storage = storage;
        this.embeddingClient = embeddingClient;
        this.ingestTaskService = ingestTaskService;
        // 编程式事务：版本切换需要"更新文档版本 + 删除旧 chunk"原子完成，@Transactional 不适合跨方法编排，
        // 用 TransactionTemplate 在方法内部精确控制事务边界。
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.redis = redis;
    }

    public void process(Long documentId) {
        process(documentId, null);
    }

    public void process(Long documentId, Long taskId) {
        if (documentId == null || documentId <= 0) {
            throw new IllegalArgumentException("documentId 必须为正数");
        }
        String lockKey = "rag2agent:ingest:lock:" + documentId;
        String lockToken = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, lockToken, INGEST_LOCK_TTL))) {
            throw new IllegalStateException("同一文档正在入库，稍后重试: " + documentId);
        }
        try {
            processLocked(documentId, taskId);
        } finally {
            redis.execute(
                    new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class),
                    List.of(lockKey), lockToken);
        }
    }

    private void processLocked(Long documentId, Long taskId) {
        // 前置校验：文档必须存在且为 PDF（第一版仅支持文本型 PDF）
        DocumentMeta document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new IllegalStateException("文档不存在: " + documentId);
        }
        if (!"pdf".equalsIgnoreCase(document.getFileType())) {
            throw new IllegalStateException("暂仅支持 PDF 入库，当前类型: " + document.getFileType());
        }

        // 幂等保护：RocketMQ 重试时同一消息可能被再次消费，
        // 任务已是 INDEXED 说明上次已成功，直接跳过避免重复入库。
        IngestTask task = taskId == null
                ? ingestTaskService.latestByDocument(documentId)
                : ingestTaskService.findById(taskId);
        if (task == null) {
            throw new IllegalStateException("入库任务不存在: " + documentId);
        }
        if (!documentId.equals(task.getDocumentId())) {
            throw new IllegalStateException("入库任务与文档不匹配: " + task.getId());
        }
        if ("INDEXED".equals(task.getStatus())) {
            log.info("任务已完成，跳过重复消费: documentId={}", documentId);
            return;
        }
        // 版本号 +1：每次成功入库递增，chunk 按版本写入/清理，支持文档重新入库不残留旧数据
        int nextVersion = (document.getVersion() == null ? 0 : document.getVersion()) + 1;
        ingestTaskService.markStage(task.getId(), "PARSING");
        documentMapper.update(null, new LambdaUpdateWrapper<DocumentMeta>()
                .eq(DocumentMeta::getId, documentId)
                .set(DocumentMeta::getStatus, "INDEXING"));

        Path tempPdf = null;
        try {
            byte[] bytes = storage.download(document.getStoragePath());
            tempPdf = Files.createTempFile("rag2agent-", ".pdf");
            Files.write(tempPdf, bytes);
            ParsedDocument parsed = new PdfBoxDocumentParser().parse(new DocumentSource(
                    String.valueOf(documentId), document.getFileName(), tempPdf.toUri(),
                    "application/pdf", Map.of()));

            // 扫描件/无文本层 PDF 会解析出空文本，0 chunk 却标 INDEXED，检索无结果但状态正常；这里提前拒绝
            if (parsed.text() == null || parsed.text().trim().length() < 50) {
                throw new IllegalStateException("PDF 提取文本为空或过短，可能为扫描件，暂不支持 OCR");
            }
            ingestTaskService.markStage(task.getId(), "SPLITTING");
            List<TextChunk> chunks = new RecursiveTextSplitter().split(parsed);
            log.info("文档 {} 切块完成: {} chunks", documentId, chunks.size());

            ingestTaskService.markStage(task.getId(), "EMBEDDING");
            // 先清理"同文档+同版本"的残留 chunk：
            // 上次可能写到一半失败（如第 N 批 embedding 挂了），重试时先删再插，保证不产生重复/残缺数据
            chunkMapper.deleteByDocumentAndVersion(documentId, nextVersion);
            persistChunks(document, chunks, nextVersion);

            // 版本切换：更新文档版本 + 删除所有旧版本 chunk，同一事务内原子完成
            switchVersion(documentId, nextVersion);
            ingestTaskService.markIndexed(task.getId());
            log.info("文档 {} 入库完成", documentId);
        } catch (Exception e) {
            // 失败终态：记录错误信息，文档标记 FAILED；异常继续抛出给消费者走 RECONSUME_LATER 重试
            ingestTaskService.markFailed(task.getId(), e.getMessage());
            documentMapper.update(null, new LambdaUpdateWrapper<DocumentMeta>()
                    .eq(DocumentMeta::getId, documentId)
                    .set(DocumentMeta::getStatus, "FAILED"));
            throw new RuntimeException("入库处理失败: " + e.getMessage(), e);
        } finally {
            // 临时 PDF 用完即删，避免磁盘残留（清理失败不影响主流程）
            if (tempPdf != null) {
                try {
                    Files.deleteIfExists(tempPdf);
                } catch (Exception ignored) {
                    // 临时文件清理失败不影响主流程
                }
            }
        }
    }

    private void persistChunks(DocumentMeta document, List<TextChunk> chunks, int version) {
        // 分批向量化：Embedding API 有请求体大小限制，每批 16 条，平衡调用次数与单次耗时；
        // 返回向量与输入文本按下标一一对应，逐条写入 document_chunk（带版本号）
        int start = 0;
        while (start < chunks.size()) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, chunks.size());
            List<String> batchTexts = chunks.subList(start, end).stream()
                    .map(TextChunk::content)
                    .toList();
            EmbeddingResponse response = embeddingClient.embed(new EmbeddingRequest(
                    embeddingClient.providerName(), null, batchTexts));
            // 防御：API 少返回向量时按输入数量写会导致后半段 chunk 静默漏写，这里先校验数量一致
            if (response.vectors().size() != batchTexts.size()) {
                throw new IllegalStateException("Embedding 返回数量不匹配: 期望 " + batchTexts.size()
                        + " 条，实际 " + response.vectors().size() + " 条");
            }
            for (int i = 0; i < response.vectors().size(); i++) {
                TextChunk chunk = chunks.get(start + i);
                chunkMapper.insertChunk(
                        document.getId(),
                        document.getKbId(),
                        chunk.position(),
                        chunk.content(),
                        chunk.content().length(),
                        toVectorString(response.vectors().get(i)),
                        null,
                        "{}",
                        version);
            }
            start = end;
        }
    }

    private void switchVersion(Long documentId, int version) {
        // 事务边界：更新文档版本/状态 + 删除旧版本 chunk 必须原子完成。
        // 若只更新版本号而旧 chunk 未删净，检索会混入上一版内容，形成"版本新、数据旧"的中间态。
        transactionTemplate.executeWithoutResult(status -> {
            int updated = documentMapper.update(null, new LambdaUpdateWrapper<DocumentMeta>()
                    .eq(DocumentMeta::getId, documentId)
                    .set(DocumentMeta::getVersion, version)
                    .set(DocumentMeta::getStatus, "INDEXED"));
            if (updated != 1) {
                throw new IllegalStateException("文档版本切换失败: " + documentId);
            }
            chunkMapper.deleteBelowVersion(documentId, version);
        });
    }

    private static String toVectorString(List<Float> vector) {
        // List<Float> -> "[0.1,0.2,0.3]"，配合 SQL 的 ::vector 转换写入 pgvector；
        // 用 join 而非 toString() 是为了去掉空格，保证 pgvector 输入格式严格兼容
        return vector.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }
}
