# 工程问题 TODO 清单

> 来源：入库链路并发/边界审查（2026-08-14）。分级：P0 并发正确性、P1 数据一致性/边界、P2 性能/安全优化。

## P0 并发正确性

- [x] **同一文档并发入库竞态**（IngestPipelineService.process）
  - 问题：`nextVersion` 是"读旧值 +1"的无锁计算；`INDEXED` 幂等检查是"先查后执行"非原子。RocketMQ 重试消息与新消息并发时，两个消费者可能同时算出相同版本、重复写入 chunk，`switchVersion` 相互覆盖。
  - 方案：按 documentId 加 Redis 分布式锁（入库锁，锁内完成整个 process）；或 `switchVersion` 改为乐观锁（`UPDATE document SET version=? WHERE id=? AND version=?`，影响行数为 0 则放弃并提示重试）。
- [x] **任务状态机更新无 CAS**（IngestTaskService）✅ 已修复（防御性）
  - 问题：`markStage/markFailed/markIndexed` 无条件 UPDATE，并发下旧任务状态可能覆盖新任务。
  - 方案：UPDATE 带前置状态条件。已落地：三个方法均加 `ne(status, 'INDEXED')`，保护 INDEXED 终态不被并发/重试覆盖；"影响行数为 0 停止处理"的完整 CAS 归入并发竞态项一并处理。
- [x] **消息只携带 documentId，未携带 taskId**（IngestMessageService）
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
- [x] **AuthService.register 并发竞态**（selectCount + insert 非原子）
  - 问题：并发注册同名用户可能同时通过 selectCount 检查，靠 DB 唯一约束兜底会抛 500 而非友好错误。
  - 方案：username 建唯一约束并捕获 DuplicateKeyException 转 BAD_REQUEST，或注册加分布式锁。
- [x] **检索/文档/Agent 链路缺 kbId 归属校验（越权）**（SearchController / DocumentController / Agent 工具层 / 审批接口）
  - 问题：登录校验已由全局 Sa-Token 拦截器覆盖，但接口只按 kbId/documentId 过滤：检索接口可查任意 kb_id；`search_knowledge_base` 工具用模型传入的 kb_id 检索；`delete_document` 工具可删任意 document_id；`/api/agent/approvals/{runId}` 不校验 run 归属当前用户。登录用户可越权访问他人知识库、审批他人操作。
  - 方案：统一 ACL——kb_id → owner、document → kb → owner、run → user 归属校验，与 KnowledgeBaseService.list 的 ACL 同批落地。
- [ ] **只读工具调用未落库审计**（AgentRunService.executeLoop）
  - 问题：非审批工具（search_knowledge_base）执行时只推送 tool_start 事件，未写 tool_call 表，工具调用轨迹不完整。
  - 方案：所有工具调用统一落库（status SUCCEEDED/FAILED），审批工具沿用 WAITING_APPROVAL 流程。

## V2 需求审查：Agent 工程可用性

> 审查时间：2026-08-19。以下状态以当前代码为准；`tech-selection.md` 中已有架构方向不等于已经实现。

### P0 上下文与评测可靠性

- [x] **模型上下文压缩与预算控制**
  - 已落地：`ContextCompactor` 使用保守 token 估算，保留系统提示、原始问题和最近工具交互；超预算时生成确定性摘要并截断单条超长消息。Agent 每轮 LLM 调用前执行压缩，审批恢复复用压缩后的 Redis 上下文。
  - 已增加：`rag2agent.agent.context-token-budget`、`max-input-chars`、`summary-max-chars`、`max-output-tokens` 配置；压缩前后 token、丢弃消息数写入 AgentStep 并暴露 Micrometer 指标。
  - 可行性：高。优先做确定性预算控制，再接可选 LLM 摘要，避免每次请求都增加一次模型调用。
  - 后续演进：继续按“系统提示/最近消息/工具结果/高相关引用”分层优化；当前使用确定性摘要和截断，待成本与质量评测后再决定是否接入可选 LLM 滚动摘要。预算按模型配置，并预留输出 token。
  - 验收：确定性压缩单测覆盖预算超限、单条超长输入和 tool 消息配对；真实 provider 400/成本回归仍需启动中间件后执行。

- [ ] **评测任务可靠性与结果对账**
  - 当前：生成评测同步接口会超过客户端读取超时；脚本 `run-state.json` 可能与数据库 `eval_run` 脱节；切块参数矩阵尚未真正可执行。
  - 方案：评测提交改为异步任务，立即返回 `runId`；增加状态/进度查询和幂等键；恢复时以 `eval_run` / `eval_case_result` 为事实源；补齐失败、取消、超时状态。切块参数通过“独立知识库/独立索引版本”运行，不能在同一库原地覆盖。
  - 验收：客户端断开不重复创建运行；同一幂等键只产生一个 run；重启后可恢复；每个 run 可查询逐题结果和错误原因。

### P0 可观测性

- [ ] **RAG 全链路追踪与结构化日志**（部分已有）
  - 当前：已有 Micrometer、OTel tracing bridge、JSON 日志；检索已补齐 route/embedding/vector/keyword/RRF/rerank 子 span，Agent 保留 LLM/tool span，MQ producer/consumer 已有 Observation，消息携带 traceId；SSE/前端显示诊断 ID。
  - 方案：采用成熟组合 Micrometer Observation + OpenTelemetry OTLP + Jaeger；为 route、embedding、vector、keyword、RRF、rerank、LLM、tool、MQ consumer 建子 span。JSON 日志统一输出 `traceId/spanId/runId/kbId/userId/provider/model/latencyMs/candidateCount/resultCount/outcome`，严禁输出 prompt、密钥和完整文档内容。
  - 当前证据：`/api/health` 已在 Jaeger 查询到 `rag2agent` HTTP span；RocketMQ broker 已在高位端口健康运行。
  - 未完成：MQ 消费端目前以消息属性携带 traceId 并建立独立 span，尚未完成跨进程 parent span 关联；完整 Agent 请求与数据库 run/step 的统一字段验收待后续补齐。

- [ ] **业务指标补全**（部分已有）
  - 当前：已有检索耗时、结果数、LLM 耗时、Token、Agent transition 指标；没有缓存命中率、provider 限流/重试/熔断、上下文压缩、队列积压等指标。
  - 方案：Micrometer 统一命名和低基数标签，补充 `cache.hit/miss`、`search.phase.duration`、`ai.request/retry/limit`、`context.tokens`、`mq.lag`；Prometheus 采集，Grafana/Jaeger 作为展示面。
  - 验收：能按 provider、模型、路由、结果状态查看 QPS、P50/P95/P99、错误率、Token 和缓存命中率。

### P1 可靠性与安全边界

- [ ] **API 限流、超时、重试、熔断和降级**
  - 当前：OkHttp 有连接/读写超时，但没有统一 Resilience4j 策略；没有用户级 API 限流，模型失败时没有稳定的错误分类和降级协议。
  - 方案：外部模型调用接 Resilience4j：连接错误/429/5xx 仅对幂等请求指数退避重试，写操作和工具副作用禁止盲目重试；连续失败触发熔断；检索降级为向量或关键词单路，生成降级为“仅返回引用/暂时无法生成”。统一 `ApiResponse` 错误码和 `Retry-After`。
  - 验收：429、超时、熔断、恢复各有集成测试；客户端收到稳定 JSON，不出现 HTML 或空响应。

- [ ] **输入防御与成本配额**
  - 当前：有基础 DTO 校验，但没有统一最大字符/token、恶意提示注入防护、单用户 Token 配额和并发限制。
  - 方案：入口做 Unicode/长度/控制字符规范化；按用户和知识库限制请求长度、检索 topK、Agent 最大步数和每日 Token；系统提示与外部文档分层，工具参数做 schema/权限校验；提示注入检测只作为风险信号，不替代权限控制。超限返回 400/413/429。
  - 验收：超长输入、控制字符、提示注入样例和高频请求均优雅拒绝或降级，不泄露系统提示和密钥。

- [ ] **端到端幂等性**
  - 当前：入库任务有部分重试幂等，但同名上传、创建知识库和 Agent 请求仍可能因网络重试产生重复业务对象；相关竞态已在本清单前文列出。
  - 方案：API 接受 `Idempotency-Key`；数据库对 `owner + key`、`kb_id + checksum` 建唯一约束；上传按文件 hash 决定复用或新版本；Agent 请求按 `userId + sessionId + clientRequestId` 去重。
  - 验收：同一请求重放只返回第一次结果；并发重放不重复上传、入库、扣费或执行工具副作用。
  - 当前残留风险：Agent 已增加同一用户同一 session 的并发 Redis 锁，可阻止双击/并发请求；但请求完成后再次重试仍会创建新 run。后续需在 `ChatRequest` 增加 `clientRequestId`，并按 `userId + clientRequestId` 做数据库/Redis 幂等去重。

- [ ] **Redis 分布式锁租约续期**
  - 当前残留风险：入库锁 TTL 为 2 小时，Agent session 锁 TTL 为 30 分钟；若模型或 embedding 链路超过 TTL，锁可能自动过期，导致第二个请求重新进入并发执行。
  - 方案：为长任务增加 watchdog/租约续期，续期脚本必须校验 token；任务结束仍使用 compare-and-delete 释放，避免误删其他请求的锁。

- [ ] **RocketMQ 跨进程 parent span 传播**
  - 当前残留风险：消息属性已携带业务 `traceId`，消费端也建立了独立 Observation，但尚未使用标准 OpenTelemetry `traceparent` 注入/提取，因此 Jaeger 中 producer 与 consumer 可能不是同一条父子链路。
  - 方案：使用 `TextMapPropagator` 将 `traceparent/tracestate` 写入 RocketMQ user properties，消费端提取后创建带 parent context 的 consumer span，并增加 Jaeger 链路验收测试。

### P1 可插拔与动态配置

- [ ] **模型热插拔与模型配置解耦**（部分已有）
  - 当前：`infra-ai` 已有 Chat/Embedding/Rerank 接口，模型名通过配置注入；但 `InfraAiAutoConfiguration` 仍按 `deepseek`/`siliconflow` 名称硬选择，新增非兼容 provider 需要改装配代码，且变更需要重启。
  - 方案：按 capability 建 provider registry，provider adapter 只实现统一接口；OpenAI-compatible provider 仅新增配置，非兼容协议新增 adapter；配置包含 active provider、model、timeout、限流策略和版本。短期支持重启生效，动态刷新作为配置中心能力单独验收。
  - 验收：新增一个 OpenAI-compatible provider 只改配置；新增非兼容 provider 只新增 adapter，不改检索/Agent 核心流程。

- [ ] **MCP 工具/知识源扩展边界**（部分已有）
  - 当前：bootstrap 有 `ToolRegistry` 和工具实现；`mcp-server` 目前只有占位 `McpToolRegistry` 接口，尚未真正完成 MCP transport、远程工具发现和超时隔离。
  - 方案：统一 `ToolDescriptor + ToolExecutor`，MCP 远程工具适配为同一执行接口；工具注册、参数 schema、权限、超时、审计和审批由核心编排层负责，具体工具只实现执行逻辑。
  - 验收：新增只读工具不改 Agent 循环；高风险工具自动进入审批；远程 MCP 超时不会拖垮主请求。

- [ ] **核心策略动态配置**
  - 当前：chunk size、topK、RRF、Rerank 模型等主要来自静态配置或请求参数，没有配置中心和版本化发布。
  - 方案：先抽象 `RuntimeConfigProvider`，本地 YAML 作为 fallback；生产接入 Nacos/Apollo 时通过版本号、灰度、回滚和 `@RefreshScope`/监听器刷新。切块参数变更必须创建新索引版本，不能在线修改旧 chunk。
  - 可行性：中等。Nacos/Apollo 接入本身可行，但会增加部署和兼容成本；V2 先完成抽象、热刷新边界和回滚，再决定是否把配置中心纳入 Docker Compose。

### P1 缓存、并发与成本

- [ ] **多级缓存**
  - 当前：Redis 只用于 Agent 审批上下文；没有 Caffeine L1、语义缓存、Embedding 查询缓存，也没有命中率指标。
  - 方案：Caffeine L1 + Redis L2；缓存 key 至少包含 provider、model、版本、规范化文本和维度；优先缓存查询 embedding、热门检索结果和只读工具结果，写入/版本切换主动失效；不缓存带权限差异的裸文档结果。
  - 验收：命中/未命中可观测，权限和文档版本不会串缓存。

- [ ] **检索并行化**
  - 当前：向量召回与关键词召回在 `HybridSearchService` 中顺序执行，Rerank 依赖融合结果必须串行。
  - 方案：使用受控 `CompletableFuture`/虚拟线程执行向量和关键词召回并行，统一超时和取消；RRF 后再串行 Rerank；禁止使用无界 common pool。
  - 验收：两路并行时端到端延迟接近较慢一路而非两路相加；任一路超时可按策略降级。

- [ ] **长文本截断、摘要与 Token 成本控制**
  - 当前：Agent 上下文没有统一 token budget；已有 Token 计数指标但没有预算拒绝、摘要、缓存抵扣或用户配额。
  - 方案：与上下文压缩共用预算器；限制单次输入/引用/输出，超过阈值先压缩；按 user/provider/model 记录 prompt/completion/cache token 和估算成本；加入每日额度、并发信号量和异常流量告警。
  - 验收：长文本不触发 provider 上限；恶意刷接口在达到配额后返回稳定 429；成本可按用户和模型追踪。
