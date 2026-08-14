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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 入库流水线：下载原文 -> 解析 -> 切块 -> Embedding（分批）-> 写 pgvector -> 状态流转。
 * 任务状态统一由 {@link IngestTaskService} 管理；任一步失败抛异常，由消费者决定重试。
 * @author 21311
 */
@Service
public class IngestPipelineService {

    private static final Logger log = LoggerFactory.getLogger(IngestPipelineService.class);
    private static final int EMBEDDING_BATCH_SIZE = 16;

    private final DocumentMetaMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final MinioStorageService storage;
    private final EmbeddingClient embeddingClient;
    private final IngestTaskService ingestTaskService;
    private final TransactionTemplate transactionTemplate;

    public IngestPipelineService(
            DocumentMetaMapper documentMapper,
            DocumentChunkMapper chunkMapper,
            MinioStorageService storage,
            EmbeddingClient embeddingClient,
            IngestTaskService ingestTaskService,
            PlatformTransactionManager transactionManager) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.storage = storage;
        this.embeddingClient = embeddingClient;
        this.ingestTaskService = ingestTaskService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void process(Long documentId) {
        DocumentMeta document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new IllegalStateException("文档不存在: " + documentId);
        }
        if (!"pdf".equalsIgnoreCase(document.getFileType())) {
            throw new IllegalStateException("暂仅支持 PDF 入库，当前类型: " + document.getFileType());
        }

        IngestTask task = ingestTaskService.latestByDocument(documentId);
        if (task == null) {
            throw new IllegalStateException("入库任务不存在: " + documentId);
        }
        if ("INDEXED".equals(task.getStatus())) {
            log.info("任务已完成，跳过重复消费: documentId={}", documentId);
            return;
        }
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

            ingestTaskService.markStage(task.getId(), "SPLITTING");
            List<TextChunk> chunks = new RecursiveTextSplitter().split(parsed);
            log.info("文档 {} 切块完成: {} chunks", documentId, chunks.size());

            ingestTaskService.markStage(task.getId(), "EMBEDDING");
            chunkMapper.deleteByDocumentAndVersion(documentId, nextVersion);
            persistChunks(document, chunks, nextVersion);

            switchVersion(documentId, nextVersion);
            ingestTaskService.markIndexed(task.getId());
            log.info("文档 {} 入库完成", documentId);
        } catch (Exception e) {
            ingestTaskService.markFailed(task.getId(), e.getMessage());
            documentMapper.update(null, new LambdaUpdateWrapper<DocumentMeta>()
                    .eq(DocumentMeta::getId, documentId)
                    .set(DocumentMeta::getStatus, "FAILED"));
            throw new RuntimeException("入库处理失败: " + e.getMessage(), e);
        } finally {
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
        int start = 0;
        while (start < chunks.size()) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, chunks.size());
            List<String> batchTexts = chunks.subList(start, end).stream()
                    .map(TextChunk::content)
                    .toList();
            EmbeddingResponse response = embeddingClient.embed(new EmbeddingRequest(
                    "siliconflow", null, batchTexts));
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
        transactionTemplate.executeWithoutResult(status -> {
            documentMapper.update(null, new LambdaUpdateWrapper<DocumentMeta>()
                    .eq(DocumentMeta::getId, documentId)
                    .set(DocumentMeta::getVersion, version)
                    .set(DocumentMeta::getStatus, "INDEXED"));
            chunkMapper.deleteBelowVersion(documentId, version);
        });
    }

    private static String toVectorString(List<Float> vector) {
        return vector.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }
}
