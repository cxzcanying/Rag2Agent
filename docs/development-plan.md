# 开发计划（第一期：可演示闭环）

> 依据：[docs/tech-selection.md](tech-selection.md) 第 7 节"第一期"范围。周期：2 周（14 天，含缓冲）。目标：本地 `docker compose up` 后，能完成"上传 PDF → 自动入库 → 混合检索问答（带引用）→ Agent 工具调用（含人工审批）"的完整演示闭环，评测集能跑出指标。
>
> 标注说明：
> - ★★★ = 实操坑密集，必须真机调试、写测试复现，预留时间；
> - ★★ = 配置/集成坑，跑通一次即可，按文档排查；
> - ★ = 常规实现，直接生成，修到无 bug 即可（前端属于此类）。

## 1. 里程碑计划

| 天 | 任务 | 等级 | 验收标准 |
|---|---|---|---|
| D1-D2 | 环境与骨架：启动 Docker Desktop，`docker compose up`（PG+pgvector/Redis/MinIO/RocketMQ/Neo4j）；现有骨架编译启动；数据库 DDL 初版（知识库/文档/chunk/任务/agent_run/step/tool_call/评测） | ★★ | 所有容器 healthy；`/api/health` UP；DDL 可执行 |
| D3-D4 | 工程底座：MyBatis-Plus 接入；多数据源读写分离预留（开关控制）；Redis 序列化配置；Sa-Token 登录；MinIO 上传 + presigned URL | ★★ | 文档上传落 MinIO、元数据落 PG；读写分离开关可切换 |
| D5-D6 | infra-ai 模型层：OpenAI 兼容 Chat（同步+SSE 流式）、Embedding、Rerank；DeepSeek V4 Flash/Pro、SiliconFlow BGE-M3/bge-reranker 配置化；Resilience4j 超时/重试/熔断；MockWebServer 测试 | ★★★ | mock 单测全绿；真实调用 chat/embedding/rerank 各一次成功 |
| D7-D8 | 文档入库 Pipeline：PDFBox 解析（文本+表格）；递归+标题感知+overlap 切块；RocketMQ 异步任务（解析→切块→embedding→pgvector 写入）；任务状态机 + 幂等 + 死信 | ★★★ | 上传 PDF 自动入库；chunk 可查询；失败任务有状态可查 |
| D9-D10 | 检索链路：Query Routing（规则+小模型分类）；BM25（pg_trgm）+ 向量 + RRF 融合 + Rerank；引用溯源 + 权限过滤 | ★★★ | 评测集问题能跑通；命中率/召回指标可量化 |
| D11-D12 | Agent 状态机 + 对话接口：AgentRun/Step/ToolCall 表 + 状态机（含 WAITING_APPROVAL）；function calling + 工具注册表（内部工具：检索/查文档）；SSE 对话接口（带引用、无引用不答）；前端联调（★ 直接生成：对话页/知识库页/审批弹窗） | ★★★ + ★ | 完整闭环演示：提问→检索→生成→工具审批→答复 |
| D13 | 评测与可观测：评测集（50-100 条）；Hit@k/MRR/Faithfulness；配置矩阵实验（切块×检索×重排）；OTel/Prometheus 指标 + JSON 日志；GitHub Actions 冒烟 CI | ★★ | 评测跑出指标；配置对比有结果；CI 绿 |
| D14 | 缓冲：实操坑回填；演示数据整理；README 更新；遗留 bug 修复 | - | 演示脚本能完整走通 |

## 2. 重点调试清单（面试高频 + 必须实操才能掌握）

| # | 问题 / 坑 | 为什么必须实操 | 面试怎么答 |
|---|---|---|---|
| 1 | SSE 流式解析：多行 `data:`、`[DONE]`、空行、断连 | mock 与真实网关的换行/分块行为不同，只有真跑才看得到 | 讲清楚解析状态机与超时/重连处理 |
| 2 | DeepSeek 模型名与返回字段差异（2026-07 停用旧名，V4 系新名） | 官方文档与实际返回偶有出入，必须真实调一次 | 强调模型名配置化 + 兼容层设计 |
| 3 | pgvector：维度不匹配、HNSW 索引参数、`binary_quantize` 效果 | 只读文档不跑会翻车：维度写错直接报错，索引参数影响召回 | 讲 HNSW m/ef 取舍与评测验证 |
| 4 | 中文全文检索分词差（PG 默认分词对中文弱） | 必须用真实中文文档实测 BM25 效果，决定是否上 zhparser/pg_jieba | 讲清楚 tsvector/pg_trgm 边界与演进判据 |
| 5 | RocketMQ 本地内存：默认 JVM 参数会 OOM | 经典坑，不设 `JAVA_OPT_EXT` 容器直接起不来 | 讲单机开发的最小内存配置 |
| 6 | function calling：模型返回非法 JSON、参数类型错 | 真实模型偶发不守 schema，必须有容错与重试 | 讲 schema 校验、重试、解析兜底 |
| 7 | 切块参数与检索效果强耦合 | 只有跑评测实验才知道 chunk size/overlap 的取舍 | 讲"切块参数由评测决定"的闭环 |
| 8 | RRF 融合与 Rerank 顺序：分数域不同 | 必须实测各路 TopK 与 k 值，否则结果不稳定 | 讲 RRF 原理 + 实测调参 |
| 9 | Agent 审批状态恢复与并发 | 状态机持久化后，重启/并发审批是实操才暴露的问题 | 讲 WAITING_APPROVAL 的持久化与幂等 |
| 10 | Embedding 批量限流与失败重试 | 真实 API 有 QPS/并发限制，批量入库必然触发 | 讲批量并发控制、指数退避、幂等 |
| 11 | MinIO presigned URL 权限模型 | 只读文档不理解"预签名 URL 过期与 ACL"的实际行为 | 讲文件访问与权限过滤链路 |
| 12 | OTel 全链路 trace 在异步任务中的传递 | 跨线程/跨 MQ 的 context 传播不实操一定漏 | 讲 traceId 贯穿 HTTP→MQ→任务的设计 |

## 3. 前端等常规部分（直接生成）

- Vue 3 + TS + Element Plus：对话页（SSE 流式渲染、引用卡片、审批弹窗）、知识库管理页（上传/状态）、评测页。
- 规则：直接生成，跑通无 bug 即可，不追求交互复杂度；所有业务逻辑必须已经在后端接口层就绪，前端只做渲染与调用。

## 4. 用户需要准备

1. 启动 Docker Desktop（当前服务是停止状态）。
2. DeepSeek API Key（https://platform.deepseek.com）。
3. SiliconFlow API Key（https://siliconflow.cn，BGE-M3 / bge-reranker 用，可选但推荐）。
4. 2-3 个中文测试 PDF（含表格更好，用于解析与检索测试）。

## 5. 依赖关系（必须先完成前序）

- D7-D8 依赖 D5-D6（embedding 可用）；
- D9-D10 依赖 D7-D8（数据能入库）；
- D11-D12 依赖 D9-D10（检索链路可用）；
- D13 评测依赖 D9-D10 的真实检索结果。

## D13 当前落地状态

- 已落地：评测用例导入、单配置运行、配置矩阵运行、Hit@k/MRR、可选 Faithfulness/Answer Correctness、逐题结果持久化。
- 已落地：Actuator health/metrics/prometheus、Micrometer 业务指标、OpenTelemetry tracing bridge、Spring Boot JSON 日志。
- 已落地：GitHub Actions 后端确定性单测/编译和前端构建冒烟。
- 待补数据：真实知识库的 50-100 条金标用例；没有金标数据就不能把评测数字当成优化结论。

## 第二版七天计划：工程可用性与 Agent 可靠性

> 目标：在现有 Agent + MCP 基础上，把“能演示”推进到“可排查、可限流、可恢复、可扩展”。不把 Nacos/Apollo、GraphRAG 等高风险扩展塞进同一周的核心验收。

| 天 | 任务 | 主要交付 | 验收标准 |
|---|---|---|---|
| D1 | 评测可靠性收口 | 异步评测 run、状态/进度查询、幂等键、数据库对账；补评测异常测试 | 客户端断开不重复 run；超时/失败状态可查询；结果可从 `eval_run` 恢复 |
| D2 | 上下文压缩与输入预算 | `ContextCompactor`、token budget、引用去重/截断、确定性摘要 fallback | 已完成代码与确定性单测；真实 provider 上的上下文边界回归待中间件/API 可用后执行 |
| D3 | 全链路追踪与日志 | Micrometer Observation 子 span、OTLP、Jaeger Compose、JSON trace 字段 | HTTP→检索→Rerank→LLM→Tool→MQ 可在 Jaeger 串联；日志可按 traceId 查询 |
| D4 | 可靠性策略 | Resilience4j 重试/熔断/降级、API 限流、统一超时错误、输入防御 | 模型 429/5xx/超时有稳定 JSON；幂等请求不重复副作用；限流返回 429 |
| D5 | 缓存与并行检索 | Caffeine L1 + Redis L2、查询 embedding/结果缓存、向量/关键词并行 | 命中率可观测；权限和版本不串缓存；两路并行延迟下降 |
| D6 | 模型与工具扩展 | provider registry、MCP transport/远程工具适配、工具 schema/审计/超时 | 新增 OpenAI-compatible provider 只改配置；新增只读工具不改 Agent 循环 |
| D7 | 集成验收与成本治理 | 公开评测回归、Token/成本/队列指标、故障演练、文档和 CI 更新 | 核心测试绿；Jaeger 有完整样例；限流、熔断、恢复、重试、压缩均有证据 |

### 第二版 D3 实际进度（全链路追踪与日志）

- 已完成：RocketMQ 开发端口切换为宿主 `19091/19111/19112`，broker 实际监听 `19111`；Jaeger all-in-one 固定为 `1.60`，UI `16686`、OTLP HTTP `4318`。
- 已完成：检索增加 `route/embedding/vector/keyword/rrf/rerank` Observation 子 span；原有 Agent 的 LLM/tool span 保留；MQ producer/consumer 增加 Observation，消息携带 `traceId`。
- 已完成：`POST /api/chat` SSE 首事件返回 traceId，前端聊天消息显示“诊断 ID”，可用于日志和 Jaeger 查询。
- 已验证：`/api/health` 返回 `UP`；Jaeger `/api/services` 已出现 `rag2agent`，可查询 HTTP span；前后端构建通过。
- 未完成：RocketMQ 当前以消息属性携带 traceId，消费端建立独立 span，尚未实现跨进程 parent span 关联；需要后续接入标准传播器后再宣称 HTTP→MQ 单 trace 串联。

### 第二版 D6 实际进度（模型与工具扩展）

- 已完成：按 chat/embedding/rerank capability 解析 active provider，主链路、缓存 key 和可观测标签不再依赖固定 provider 名称；新增 OpenAI-compatible provider 只需增加配置并切换 active 项。
- 已完成：内部工具和可选 MCP 工具统一进入 `ToolRegistry`，工具参数按 descriptor schema 做必填项和基础类型校验；新增只读工具无需修改 Agent 循环。
- 已完成：`ToolExecutor` 统一执行权限钩子、有界线程池、超时、Observation/Micrometer 和 `tool_call` 成功/失败/超时审计；高风险工具继续复用现有审批记录，不重复创建调用记录。
- 已完成：MCP 远程工具发现/调用契约和本地适配；远程发现失败时保留本地工具，远程调用受统一超时隔离。
- 待后续：真实 MCP 网络 transport、认证和服务端权限实现；非 OpenAI-compatible provider 仍需新增协议 adapter，动态刷新仍归配置中心阶段。

### 第二版 D7 实际进度（集成验收与成本治理）

- 已完成：AI 请求和 Agent Token 指标统一带 `provider/model/operation/outcome` 低基数标签，完整响应的 `prompt_tokens/completion_tokens/total_tokens` 会进入 Micrometer。
- 已验证：infra-ai、rag-core、bootstrap 的确定性单测通过；应用上下文测试仅因本机 MinIO 未启动失败。
- 未完成：流式 usage 解析、真实 provider 成本账本、队列积压指标、公开评测回归、RocketMQ→Jaeger 父子链路和故障演练仍需中间件及真实 API 条件，保留在 TODO。

### V2 范围裁决

- **本周必须做**：评测异步化、上下文预算、OTel/Jaeger 链路、结构化日志、限流/超时/重试/降级、幂等、Token 与缓存指标。
- **本周做抽象但不强行上线**：Nacos/Apollo 动态配置、非兼容模型 adapter、远程 MCP transport；先保证接口边界和本地 fallback。
- **暂不纳入七天核心验收**：GraphRAG、复杂提示注入分类模型、完整语义缓存平台、跨地域配置中心高可用。这些可在 V2.1 继续推进。
