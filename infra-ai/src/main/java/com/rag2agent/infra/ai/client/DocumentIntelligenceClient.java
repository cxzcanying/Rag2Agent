package com.rag2agent.infra.ai.client;

import com.rag2agent.infra.ai.model.DocumentIntelligenceResult;
import java.nio.file.Path;

/** 文档智能服务的稳定边界；当前实现为 Docling Serve。 */
public interface DocumentIntelligenceClient {

    DocumentIntelligenceResult convert(Path file, String fileName, String contentType);
}
