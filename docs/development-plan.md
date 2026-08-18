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
