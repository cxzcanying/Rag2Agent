# 工程问题 TODO 清单

> 来源：入库链路并发/边界审查（2026-08-14）。分级：P0 并发正确性、P1 数据一致性/边界、P2 性能/安全优化。

## P0 并发正确性

- [ ] **同一文档并发入库竞态**（IngestPipelineService.process）
  - 问题：`nextVersion` 是"读旧值 +1"的无锁计算；`INDEXED` 幂等检查是"先查后执行"非原子。RocketMQ 重试消息与新消息并发时，两个消费者可能同时算出相同版本、重复写入 chunk，`switchVersion` 相互覆盖。
  - 方案：按 documentId 加 Redis 分布式锁（入库锁，锁内完成整个 process）；或 `switchVersion` 改为乐观锁（`UPDATE document SET version=? WHERE id=? AND version=?`，影响行数为 0 则放弃并提示重试）。
- [x] **任务状态机更新无 CAS**（IngestTaskService）✅ 已修复（防御性）
  - 问题：`markStage/markFailed/markIndexed` 无条件 UPDATE，并发下旧任务状态可能覆盖新任务。
  - 方案：UPDATE 带前置状态条件。已落地：三个方法均加 `ne(status, 'INDEXED')`，保护 INDEXED 终态不被并发/重试覆盖；"影响行数为 0 停止处理"的完整 CAS 归入并发竞态项一并处理。
- [ ] **消息只携带 documentId，未携带 taskId**（IngestMessageService）
  - 问题：消费端 `latestByDocument` 取"最新任务"，同一文档存在多个任务（重复上传 + 失败遗留）时，重试可能处理到错误任务。
  - 方案：消息体改为 `{documentId, taskId}`，process 按 taskId 直接定位任务。
- [x] **Agent 审批并发无防护**（AgentRunService.approve）✅ 已修复
  - 问题：并发审批同一 runId 会重复执行工具、重复向消息历史追加工具结果，模型上下文被污染。
  - 方案：CAS 抢占——approve 前把 run 状态从 WAITING_APPROVAL 置为 EXECUTING，影响行数为 0 则拒绝。已落地。

## P1 数据一致性 / 边界

- [x] **上传链路非原子，消息发送失败产生脏数据**（DocumentService.upload）✅ 已修复
  - 问题：document + ingest_task 落库成功后发送 RocketMQ 消息；发送失败抛 500，但库里已留下 UPLOADED 文档 + PENDING 任务，消息永远不补发。
  - 方案：发送失败时同步标记任务 FAILED 并重抛。已落地：`upload` 捕获发送异常后 `markFailed(taskId, ...)`，避免遗留 PENDING 脏数据。
- [x] **空文本文档被"成功"入库**（IngestPipelineService / PdfBoxDocumentParser）✅ 已修复
  - 问题：扫描件/无文本 PDF 解析出空文本 → 0 chunk 却标记 INDEXED，检索无结果但状态正常。
  - 方案：解析后校验文本长度（< 50 字符视为无效）。已落地：`process` 解析后校验 `parsed.text()`，空则抛异常走 FAILED。
- [ ] **同名文件重复上传总是新建文档**（DocumentService.upload）
  - 问题：document.version 版本机制只在重试时自增，没有覆盖"文档更新/同名替换"场景。
  - 方案：按 kb_id + file_name 决定"新建或版本化更新"，明确产品语义。
- [x] **失败任务无手动重试入口** ✅ 已修复
  - 问题：失败后只能靠 RocketMQ 自动重试（16 次后进死信），没有人工触发入口。
  - 方案：提供 `POST /api/documents/{id}/reingest`。已落地：`DocumentService.reingest` 重置任务为 PENDING 并重发消息，`DocumentController` 暴露端点。
- [x] **检索入口参数未校验**（SearchController / HybridSearchService.search）✅ 已修复
  - 问题：`kbId` 为 null 直接进 SQL；`query` 为 null/空白时仍会调 embedding 白花额度，且 keywordSearch 的 `ILIKE '%'||''||'%'` 会退化成全表匹配；`topK<=0` 会让 RRF 的 `limit` 抛异常、超大值会拉回海量数据。
  - 方案：入口统一校验。已落地：`SearchController` 校验 `kbId` 非空、`query` 非空白、`topK` 1~100，非法抛 BAD_REQUEST。
- [x] **persistChunks 未校验 embedding 返回数量与输入一致**（IngestPipelineService.persistChunks）✅ 已修复
  - 问题：写入循环以 `response.vectors().size()` 为边界，若 API 少返回向量，后半段 chunk 会被静默漏写且不报错，最终文档仍标记 INDEXED 但 chunk 不全。
  - 方案：写入前校验数量一致。已落地：`vectors.size() != batchTexts.size()` 时抛异常走重试。
- [x] **rerank 结果 index 越界且未显式排序**（HybridSearchService.rerank）✅ 已修复
  - 问题：`fused.get(result.index())` 在 rerank API 返回越界 index 时会抛 IndexOutOfBoundsException；结果直接 `map` 保留原顺序，若 provider 未按分数降序返回则结果乱序。
  - 方案：校验 `index` 落在 `[0, fused.size())` 并按分数降序排序。已落地：`filter` 越界 + `sorted(score.reversed())`。
- [x] **keywordSearch 的 ILIKE 未转义 %/_ 通配符**（HybridSearchService.keywordSearch）✅ 已修复
  - 问题：query 中含 `%` 或 `_` 会被当作 SQL LIKE 通配符，造成误匹配或漏匹配。
  - 方案：ILIKE 前转义。已落地：新增 `escapeLike`，对 `\`、`%`、`_` 转义后传给 ILIKE。

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
- [ ] **tokenCount 用字符近似真实 token**
  - 问题：`tokenCount` 用 `content.length()` 是字符数而非真实 token，英文/混合文本偏差较大。
  - 方案：评测阶段用真实 tokenizer（或按语言分治估算）校准。

> 注：overlap 拼接使 chunk 长度上限为 `chunkSize + overlap`，这是设计行为（`RecursiveTextSplitterTest` 已用 `<= chunkSize+overlap` 断言），不是缺陷。
- [ ] **SSE 流式解析未覆盖多行 data 与断连重连**（OpenAiChatModelClient.stream）
  - 问题：只认单行 `data:` 前缀，多行 data、`event:` 字段、断连半包都未处理；真实网关分块行为与 mock 不同。
  - 方案：实现 SSE 事件状态机（data 多行聚合、`[DONE]` 终止、超时/断连重连），D11-D12 对话接口前补齐。
- [ ] **AuthService.register 并发竞态**（selectCount + insert 非原子）
  - 问题：并发注册同名用户可能同时通过 selectCount 检查，靠 DB 唯一约束兜底会抛 500 而非友好错误。
  - 方案：username 建唯一约束并捕获 DuplicateKeyException 转 BAD_REQUEST，或注册加分布式锁。
- [ ] **检索/文档/Agent 链路缺 kbId 归属校验（越权）**（SearchController / DocumentController / Agent 工具层 / 审批接口）
  - 问题：登录校验已由全局 Sa-Token 拦截器覆盖，但接口只按 kbId/documentId 过滤：检索接口可查任意 kb_id；`search_knowledge_base` 工具用模型传入的 kb_id 检索；`delete_document` 工具可删任意 document_id；`/api/agent/approvals/{runId}` 不校验 run 归属当前用户。登录用户可越权访问他人知识库、审批他人操作。
  - 方案：统一 ACL——kb_id → owner、document → kb → owner、run → user 归属校验，与 KnowledgeBaseService.list 的 ACL 同批落地。
- [ ] **只读工具调用未落库审计**（AgentRunService.executeLoop）
  - 问题：非审批工具（search_knowledge_base）执行时只推送 tool_start 事件，未写 tool_call 表，工具调用轨迹不完整。
  - 方案：所有工具调用统一落库（status SUCCEEDED/FAILED），审批工具沿用 WAITING_APPROVAL 流程。
