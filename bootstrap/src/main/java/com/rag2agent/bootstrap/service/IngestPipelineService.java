package com.rag2agent.bootstrap.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.rag2agent.bootstrap.entity.DocumentMeta;
import com.rag2agent.bootstrap.entity.IngestTask;
import com.rag2agent.bootstrap.mapper.DocumentChunkMapper;
import com.rag2agent.bootstrap.mapper.DocumentMetaMapper;
import com.rag2agent.bootstrap.mapper.IngestTaskMapper;
import com.rag2agent.bootstrap.storage.MinioStorageService;
import com.rag2agent.infra.ai.client.EmbeddingClient;
import com.rag2agent.infra.ai.model.EmbeddingRequest;
import com.rag2agent.infra.ai.model.EmbeddingResponse;
import com.rag2agent.rag.core.document.DocumentSource;
import com.rag2agent.rag.core.document.ParsedDocument;
import com.rag2agent.rag.core.document.impl.PdfBoxDocumentParser;
import com.rag2agent.rag.core.split.TextChunk;
import com.rag2agent.rag.core.split.impl.RecursiveTextSplitter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 入库流水线：下载原文 -> 解析 -> 切块 -> Embedding（分批）-> 写 pgvector -> 状态流转。
 * 任一步失败抛异常，由消费者决定重试（RECONSUME_LATER）。
 */
@Service
public class IngestPipelineService {

    private static final Logger log = LoggerFactory.getLogger(IngestPipelineService.class);
    private static final int EMBEDDING_BATCH_SIZE = 16;

    private final DocumentMetaMapper documentMapper;
    private final IngestTaskMapper taskMapper;
    private final DocumentChunkMapper chunkMapper;
    private final MinioStorageService storage;
    private final EmbeddingClient embeddingClient;

    public IngestPipelineService(
            DocumentMetaMapper documentMapper,
            IngestTaskMapper taskMapper,
            DocumentChunkMapper chunkMapper,
            MinioStorageService storage,
            EmbeddingClient embeddingClient) {
        this.documentMapper = documentMapper;
        this.taskMapper = taskMapper;
        this.chunkMapper = chunkMapper;
        this.storage = storage;
        this.embeddingClient = embeddingClient;
    }

    public void process(Long documentId) {
        DocumentMeta document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new IllegalStateException("文档不存在: " + documentId);
        }
        if (!"pdf".equalsIgnoreCase(document.getFileType())) {
            throw new IllegalStateException("暂仅支持 PDF 入库，当前类型: " + document.getFileType());
        }

        IngestTask task = latestTask(documentId);
        taskMapper.update(null, new LambdaUpdateWrapper<IngestTask>()
                .eq(IngestTask::getId, task.getId())
                .set(IngestTask::getStatus, "PARSING")
                .set(IngestTask::getCurrentStage, "PARSING"));
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

            markStage(task.getId(), "SPLITTING");
            List<TextChunk> chunks = new RecursiveTextSplitter().split(parsed);
            log.info("文档 {} 切块完成: {} chunks", documentId, chunks.size());

            markStage(task.getId(), "EMBEDDING");
            persistChunks(document, chunks);

            documentMapper.update(null, new LambdaUpdateWrapper<DocumentMeta>()
                    .eq(DocumentMeta::getId, documentId)
                    .set(DocumentMeta::getStatus, "INDEXED"));
            taskMapper.update(null, new LambdaUpdateWrapper<IngestTask>()
                    .eq(IngestTask::getId, task.getId())
                    .set(IngestTask::getStatus, "INDEXED")
                    .set(IngestTask::getCurrentStage, "INDEXED")
                    .set(IngestTask::getErrorMessage, null));
            log.info("文档 {} 入库完成", documentId);
        } catch (Exception e) {
            taskMapper.update(null, new LambdaUpdateWrapper<IngestTask>()
                    .eq(IngestTask::getId, task.getId())
                    .set(IngestTask::getStatus, "FAILED")
                    .set(IngestTask::getErrorMessage, truncate(e.getMessage())));
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

    private void persistChunks(DocumentMeta document, List<TextChunk> chunks) {
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
                    "{}");
        }
            start = end;
        }
    }

    private IngestTask latestTask(Long documentId) {
        return taskMapper.selectOne(new LambdaQueryWrapper<IngestTask>()
                .eq(IngestTask::getDocumentId, documentId)
                .orderByDesc(IngestTask::getId)
                .last("LIMIT 1"));
    }

    private void markStage(Long taskId, String stage) {
        taskMapper.update(null, new LambdaUpdateWrapper<IngestTask>()
                .eq(IngestTask::getId, taskId)
                .set(IngestTask::getStatus, stage)
                .set(IngestTask::getCurrentStage, stage));
    }

    private static String toVectorString(List<Float> vector) {
        return vector.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= 2000) {
            return message;
        }
        return message.substring(0, 2000);
    }
}
