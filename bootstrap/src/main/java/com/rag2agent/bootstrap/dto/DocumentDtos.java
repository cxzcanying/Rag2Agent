package com.rag2agent.bootstrap.dto;

import com.rag2agent.bootstrap.entity.DocumentMeta;
import java.time.Instant;

public final class DocumentDtos {

    private DocumentDtos() {}

    public record DocumentView(
            Long id, Long kbId, String fileName, String fileType, Long fileSize, String status, Instant createdAt) {
        public static DocumentView from(DocumentMeta doc) {
            return new DocumentView(
                    doc.getId(),
                    doc.getKbId(),
                    doc.getFileName(),
                    doc.getFileType(),
                    doc.getFileSize(),
                    doc.getStatus(),
                    doc.getCreatedAt());
        }
    }

    public record PresignResponse(String url, int expiresInSeconds) {}
}
