# 工程问题 TODO 清单

> 来源：入库链路并发/边界审查（2026-08-14）。分级：P0 并发正确性、P1 数据一致性/边界、P2 性能/安全优化。

## P0 并发正确性

- [ ] **同一文档并发入库竞态**（IngestPipelineService.process）
  - 问题：`nextVersion` 是"读旧值 +1"的无锁计算；`INDEXED` 幂等检查是"先查后执行"非原子。RocketMQ 重试消息与新消息并发时，两个消费者可能同时算出相同版本、重复写入 chunk，`switchVersion` 相互覆盖。
  - 方案：按 documentId 加 Redis 分布式锁（入库锁，锁内完成整个 process）；或 `switchVersion` 改为乐观锁（`UPDATE document SET version=? WHERE id=? AND version=?`，影响行数为 0 则放弃并提示重试）。
- [ ] **任务状态机更新无 CAS**（IngestTaskService）
  - 问题：`markStage/markFailed/markIndexed` 无条件 UPDATE，并发下旧任务状态可能覆盖新任务。
  - 方案：UPDATE 带前置状态条件（如 `WHERE id=? AND status IN ('PENDING','PARSING',...)`），影响行数为 0 说明状态已变化，停止处理。
- [ ] **消息只携带 documentId，未携带 taskId**（IngestMessageService）
  - 问题：消费端 `latestByDocument` 取"最新任务"，同一文档存在多个任务（重复上传 + 失败遗留）时，重试可能处理到错误任务。
  - 方案：消息体改为 `{documentId, taskId}`，process 按 taskId 直接定位任务。

## P1 数据一致性 / 边界

- [ ] **上传链路非原子，消息发送失败产生脏数据**（DocumentService.upload）
  - 问题：document + ingest_task 落库成功后发送 RocketMQ 消息；发送失败抛 500，但库里已留下 UPLOADED 文档 + PENDING 任务，消息永远不补发。
  - 方案：发送失败时同步标记任务 FAILED（或删除任务）并告警；或增加补偿任务（启动/定时扫描 PENDING 超时任务重发消息）。
- [ ] **空文本文档被"成功"入库**（IngestPipelineService / PdfBoxDocumentParser）
  - 问题：扫描件/无文本 PDF 解析出空文本 → 0 chunk 却标记 INDEXED，检索无结果但状态正常。
  - 方案：解析后校验文本长度（如 < 50 字符视为无效），空则任务 FAILED 并提示暂不支持 OCR。
- [ ] **同名文件重复上传总是新建文档**（DocumentService.upload）
  - 问题：document.version 版本机制只在重试时自增，没有覆盖"文档更新/同名替换"场景。
  - 方案：按 kb_id + file_name 决定"新建或版本化更新"，明确产品语义。
- [ ] **失败任务无手动重试入口**
  - 问题：失败后只能靠 RocketMQ 自动重试（16 次后进死信），没有人工触发入口。
  - 方案：提供 `POST /api/documents/{id}/reingest`：重置任务状态为 PENDING 并重发消息。

## P2 性能 / 安全（后续阶段）

- [ ] **chunk 逐条 INSERT 性能**（IngestPipelineService.persistChunks）：1310 条逐条插入，可改 MyBatis 批量插入（foreach / JDBC batch）。
- [ ] **MinIO 下载全量读内存**（MinioStorageService.download）：`readAllBytes` 在 50MB 上限时内存峰值高，可改流式下载 + 解析。
- [ ] **知识库列表未按 owner 过滤**（KnowledgeBaseService.list）：ACL 未实现，后续按 owner_user_id 过滤。
- [ ] **登录接口无限流/失败锁定**（AuthController）：后续 Phase 6 加用户级限流与失败次数锁定。
- [ ] **查询增强实验：HyDE / Query Rewriting**（HybridSearchService.search）
  - 背景：疑问句直接向量化时，问题与文档（陈述句）在向量空间存在距离；HyDE 先让 LLM 生成"假设答案"再向量化检索，Query Rewriting 把问题改写为关键词句。
  - 方案：D13 评测阶段跑 A/B 对比（同一评测集：直接向量检索 vs HyDE 改写后检索），Hit@k/MRR 明显提升且成本可接受再接入；注意假设答案幻觉可能带偏检索方向。
- [ ] **中文关键词路增强：zhparser / pg_jieba 分词**（keywordSearch）
  - 背景：pg_trgm 按字符三元组匹配，对中文基本无语义能力（两字词 trigram 太少、同义表达失效），只对编号/API 名等精确字面有效。
  - 方案：D13 评测阶段 A/B 对比（pg_trgm vs 中文分词 + tsvector/ts_rank），中文关键词路 Hit@k/MRR 明显提升再接入；备选 OpenSearch（成本更高）。
