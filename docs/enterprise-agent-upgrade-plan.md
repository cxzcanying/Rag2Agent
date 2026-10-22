# 企业级多模态 Agent 升级计划

> 状态：Phase 2/4 实施中。
>
> 更新日期：2026-10-22。
>
> 目标：在现有文本 RAG、Hybrid Search、受控 Agent、MCP、OTel 与评测闭环之上，补齐多模态文档理解、跨页表格完整性、知识图谱、推理模式、持久 Agent Runtime、受控多 Agent 编排和企业治理能力。
>
> 本文定义功能范围、技术选型、数据契约、阶段依赖和验收标准；实现进度以各阶段“当前进度”为准。

## 1. 建设结论

本轮建设采用四条主线：

1. **多模态文档智能**：统一解析 PDF、扫描件、图片和 Office 文档，保留版面、页码、区域坐标与原始证据。
2. **表格与分页完整性**：把表格作为独立逻辑对象，解决跨页续表、重复表头、合并单元格和分页上下文丢失。
3. **受控 Agent Runtime**：持久化计划、事件、检查点、工具、审批和产物，支持推理模式、恢复、中断和多 Agent 协作。
4. **GraphRAG 与治理闭环**：建立带证据来源的企业知识图谱，支持局部路径检索与全局社区归纳，并通过租户权限、成本、追踪和评测控制风险。

技术选型继续围绕当前 Spring Boot 主链路，不引入新的通用 Agent 框架或向量数据库。复杂文档解析放到独立的文档智能 sidecar 中，Java 侧保留业务编排、权限、审计和持久化控制权。

## 2. 建设范围与取舍

### 2.1 必选能力

以下能力是本轮企业级升级的完成边界。

| 编号 | 能力簇 | 必须完成的内容 |
|---|---|---|
| M | 多模态文档智能 | 多格式解析、版面分析、OCR、图片/图表语义、区域证据、入库幂等与质量评测 |
| T | 表格与分页完整性 | 逻辑表格、跨页合并、表头传播、稳定行 ID、分页游标、Agent 表格分页工具、确定性计算 |
| G | 知识图谱 | 实体/关系抽取、实体消解、证据边、增量更新、局部图检索、权限与故障降级 |
| R | 推理模式 | 模型能力注册、推理档位、动态路由、预算、降级、成本质量评测 |
| A | Agent Runtime | 持久事件、计划执行验证、检查点、恢复、中断、工具审批、上下文治理、产物管理 |
| E | 企业治理 | 租户与 ACL、审计、追踪、模型网关、版本管理、评测、安全边界、运行恢复 |

### 2.2 选定的高价值增强项

| 编号 | 选定的增强能力 | 选择理由 |
|---|---|---|
| X1 | Coordinator-Worker-Reviewer 多 Agent 编排 | 能展示并行、上下文隔离、独立验证和预算控制，复杂度可控 |
| X2 | 长期记忆与情景记忆 | 与持久会话互补，可展示作用域、来源、冲突、TTL 和记忆投毒防护 |
| X3 | Skill 能力包 | 把可复用提示词、工具白名单、输入输出契约和验收规则版本化 |
| X4 | Agent 轨迹评测 | 从只评检索结果升级为评任务成功率、工具正确性、步数、恢复和成本 |
| X5 | 增量入库与模型迁移 | 覆盖文档更新、Embedding 升级、图数据增量和索引重建 |
| X6 | 租户、文档、图谱统一权限 | 把当前 owner 过滤升级为可验证的企业隔离边界 |
| X7 | 模型网关与配置版本 | 把模型、Prompt、Agent 和检索参数纳入版本、灰度和回滚 |
| X8 | GraphRAG 全局社区归纳 | 补足跨文档主题归纳能力，与局部多跳检索形成完整图能力 |

### 2.3 本轮范围边界

- 文档范围覆盖 PDF、扫描件、PNG/TIFF/JPEG、DOCX、PPTX、XLSX、HTML 和邮件。
- Agent 输入范围覆盖文本、图片、PDF 页面和表格区域。
- 多 Agent 本轮只实现一个有业务收益的 Coordinator-Worker-Reviewer 拓扑。
- 图谱本轮同时实现局部检索和全局社区总结，但不建设通用企业本体设计器。
- 音视频解析、跨组织 Agent 协议、插件市场和完整低代码编排保留为后续能力。

### 2.4 当前基线

以下能力已经存在，本轮在其上扩展：

- 文本型 PDF 上传、MinIO 存储、RocketMQ 异步入库和中断恢复。
- 向量与关键词并行检索、RRF 融合、可选 Rerank、版本过滤和知识库过滤。
- AgentRun/Step/ToolCall 状态、上下文压缩、人工审批、幂等、步数和工具数上限。
- 内部工具注册表、项目内 MCP JSON-RPC HTTP 子集、工具 Schema 和审计。
- SSE 断线回放、prompt injection 规则信号、限流和 AI 降级。
- OpenTelemetry、Micrometer、Prometheus、Jaeger 和结构化日志。
- 检索评测、配置矩阵、异步评测恢复和中文检索 A/B 证据。

## 3. 总体架构

```text
Vue 3 Web
   |
Spring Boot bootstrap
   |-- 文档入库编排 / 状态机 / 幂等
   |-- 多模态检索与上下文组装
   |-- 持久 Agent Runtime / 多 Agent 编排
   |-- 权限、审计、成本与评测
   |
   +-- rag-core
   |     |-- 文档元素、表格、证据与检索领域模型
   |     |-- 切块、融合、上下文扩展接口
   |
   +-- infra-ai
   |     |-- Chat / Vision / Embedding / Rerank / Reasoning 适配
   |     |-- Docling HTTP 客户端
   |
   +-- mcp-server
   |     |-- MCP Java SDK 2.0 / Streamable HTTP
   |
   +-- PostgreSQL + pgvector
   |-- Redis
   |-- MinIO
   |-- RocketMQ
   |-- Neo4j + GDS
   |-- docling-serve
```

### 3.1 模块职责

| 模块 | 本轮职责 |
|---|---|
| `framework` | 通用响应、异常与基础设施配置 |
| `infra-ai` | 模型能力适配、Vision 调用、Docling 客户端、结构化输出与模型参数映射 |
| `rag-core` | 文档元素、表格、证据、切块、图检索接口和上下文模型 |
| `mcp-server` | 官方 MCP Java SDK 接入、Streamable HTTP、工具与资源生命周期 |
| `bootstrap` | 入库编排、检索、Agent Runtime、图链路、持久化、权限、审计和评测 |
| `web` | 多模态引用、表格分页、审批、任务进度、轨迹和评测结果展示 |

首期不新增 Maven 模块。代码规模或部署边界需要独立演进时，再评估拆分 `infra-document` 或 `infra-graph`。

## 4. 技术方案选型

### 4.1 核心技术决策

| 能力 | 选定方案 | 关键理由 |
|---|---|---|
| 后端主链路 | Java 21 + Spring Boot 3.5 | 沿用现有事务、权限、线程池、观测和测试体系 |
| 文档解析 | `docling-serve` 独立容器 | 支持多格式、版面、阅读顺序、表格、OCR、图片和 DoclingDocument JSON |
| PDF 轻量路径 | 保留 PDFBox | 文本型 PDF 可快速处理，sidecar 故障时有明确降级路径 |
| OCR | Docling + RapidOCR | 支持中英文、坐标与置信度，部署形态简单 |
| 表格结构 | Docling TableFormer accurate 模式 | 保留表格结构、单元格和来源区域，后续由 Java 完成跨页合并 |
| 视觉理解 | Qwen3-VL 系列，能力名 `VISION` | 支持文档截图、图表、图像描述、结构化输出和思考模式，模型 ID 配置化 |
| 文本与表格 Embedding | 现有 BGE-M3 + pgvector | 复用现有向量基础设施；表格转为带完整表头语义的行级文本 |
| 图片检索 | OCR + 图表理解 + VLM 描述形成统一文本检索视图 | 与现有 BGE-M3 共用向量空间，原始图片和裁剪保留在 MinIO |
| Rerank | 现有 `bge-reranker-v2-m3` | 无需新增检索重排链路 |
| 元数据与事件 | PostgreSQL 17 | 事务、版本、审批、事件和评测需要强一致持久化 |
| 向量 | pgvector 0.8 | 文档、表格行和记忆共用现有数据库与版本控制 |
| 图数据库 | Neo4j 5 Community + GDS | 支持属性图、受限 Cypher、中心度和 Leiden 社区发现 |
| 异步任务 | RocketMQ 5 | 复用现有入库、重试、死信和 trace 传播 |
| 持久 Agent | 自建事件溯源状态机 | 与当前 Java 架构一致，可精确控制审批、恢复、预算和审计 |
| 多 Agent | 自建 Coordinator-Worker-Reviewer | 只落地一个有指标支撑的拓扑，避免通用编排平台膨胀 |
| 工具协议 | MCP Java SDK 2.0 | 官方 Streamable HTTP、Schema 校验、资源、提示词与能力协商完整 |
| 观测 | OpenTelemetry + Micrometer + Prometheus + Jaeger | 沿用现有全链路追踪与指标 |
| 评测 | 现有 Java 评测框架扩展 | 复用任务恢复、结果持久化和配置矩阵，不引入第二套评测平台 |
| 前端 | Vue 3 + TypeScript + Element Plus | 沿用现有技术栈，增加引用区域、表格分页和轨迹视图 |

### 4.2 文档智能选型

**采用 Docling Serve。**

- 通过 HTTP 上传原始文件，要求返回 lossless JSON、页面图片和表格结构。
- OCR 使用 RapidOCR，启用中文语言配置和表格 accurate 模式。
- 解析结果先保存原始 Docling JSON，再映射为项目内部 `document_element`、`document_table` 和 `document_asset`。
- 解析、OCR、VLM 描述均记录模型版本、参数版本和处理耗时。
- Docling 服务不可用时，文本型 PDF 使用 PDFBox 轻量路径；无法安全解析的文件进入失败状态并保留错误原因。
- sidecar 使用固定镜像摘要、CPU/内存上限、并发上限、请求超时和临时文件清理策略。

备选方案通过离线 Gold Set 评估，不进入首期生产依赖：

- PaddleOCR PP-StructureV3：作为中文复杂表格和公式场景的对照基线。
- 纯 PDFBox：作为无 sidecar 环境的快速路径。
- 纯 VLM 整页解析：作为异常文档的受控兜底，不作为全量默认链路。

### 4.3 Agent Runtime 选型

**采用现有 Spring Boot 持久状态机扩展。**

| 方案 | 结论 | 原因 |
|---|---|---|
| 当前状态机扩展 | 采用 | 与现有表结构、审批、鉴权、审计和 OTel 集成，迁移成本最低 |
| Temporal | 暂不引入 | 工作流能力强，但会增加部署、版本兼容和调试成本 |
| LangGraph | 暂不引入 | 架构表达清晰，但会形成 Java 主链路之外的异构运行时 |
| OpenAI Agents SDK | 借鉴模型 | Sessions、Guardrails、RunState、Handoff 和 Tracing 作为契约参考 |

核心变化：

- `agent_run` 继续表示一次执行，新增不可变 `agent_event` 作为权威事件日志。
- Redis Stream 只负责向浏览器实时投递 SSE，不作为执行状态唯一来源。
- 检查点保存事件序号、任务图、消息摘要、工具结果引用、审批状态和预算。
- 每个工具调用记录规范化参数摘要和摘要哈希；审批和实际执行都绑定该摘要。
- 计划、任务、子 Agent、产物和记忆使用独立对象，避免继续把所有状态塞进 JSON 字段。

### 4.4 推理模式选型

模型能力不直接写死在业务代码中：

- `infra-ai` 增加能力维度：`CHAT`、`VISION`、`EMBEDDING`、`RERANK`。
- 每个模型声明支持的推理档位、是否支持思考模式、最大上下文、最大输出、结构化输出和图片输入。
- 请求只传统一逻辑档位，由 Provider Adapter 映射为 `reasoning_effort`、`enable_thinking` 或供应商等价参数。
- 响应保存推理摘要、推理 Token、结束原因和能力来源；原始隐式思维内容不进入普通用户界面。
- 路由根据任务类型、工具数量、风险、上下文长度、延迟和费用预算选择模型与推理档位。

### 4.5 图与多 Agent 选型

- 图查询由项目内模板和参数化 Cypher 提供，不向模型开放任意 Cypher。
- 实体消解采用“规范 ID/别名优先 + BGE-M3 相似度 + 灰区 LLM 仲裁”的组合策略。
- 社区发现使用 Neo4j GDS Leiden，并保留社区到实体和原始证据的映射。
- Coordinator 负责规划和汇总，Worker 拥有独立上下文与工具白名单，Reviewer 只做证据、遗漏和冲突检查。
- 多 Agent 任务使用数据库任务图作为权威状态，通过 RocketMQ 投递异步任务，通过 outbox 避免数据库提交与消息发送不一致。

## 5. 功能清单

### 5.1 多模态文档入库

| 编号 | 功能 | 技术方案 | 验收标准 |
|---|---|---|---|
| M1 | 统一文档元素模型 | `document_element` 保存页面、标题、段落、表格、图片、图表和公式，并带父子层级 | 每个元素可定位到文档版本、页码和原始区域 |
| M2 | 多格式上传 | 上传层按 MIME、扩展名、文件头和大小校验 | PDF、图片、DOCX、PPTX、XLSX、HTML、EML 至少各有一条端到端样例 |
| M3 | 版面与阅读顺序 | Docling 输出布局、阅读顺序和层级 | 双栏、标题、页眉页脚和图文混排样例的顺序可核对 |
| M4 | OCR | RapidOCR 输出文本、坐标、置信度和语言 | 扫描件入库后能检索文字，并保留低置信度标记 |
| M5 | 图片与图表语义 | VLM 生成结构化描述、图像文字、图表数据和关键结论 | 图表问题可检出对应图片及描述，引用返回裁剪区域 |
| M6 | 原始资产留存 | 页面图、元素裁剪、图片和解析 JSON 存入 MinIO | 资产具备 checksum、MIME、页码、bbox 和来源版本 |
| M7 | 解析适配与降级 | Docling 主路径，PDFBox 轻量路径，失败任务可重试 | 服务不可用、超时、损坏文件均产生明确状态和错误码 |
| M8 | 入库幂等 | 复用文档锁、任务状态机与文件 checksum | 重复消息不产生重复元素、表格、资产或向量 |
| M9 | 版本化重建 | 文档版本递增，旧元素和旧资产按版本切换 | 重建失败不影响当前可检索版本 |
| M10 | 多模态检索 | 文本、OCR、表格行和视觉描述统一生成可检索文本视图 | 文本问题、图片问题、图表问题和扫描件问题均可召回正确来源 |
| M11 | 区域级引用 | 引用返回文档、版本、页码、bbox、资产和元素 ID | 前端可预览页面并高亮来源区域 |
| M12 | 模型版本与重建 | 记录解析、OCR、Vision、Embedding 和 Rerank 版本 | 任一模型升级后可定位受影响文档并批量重建 |

### 5.2 表格与分页完整性

| 编号 | 功能 | 技术方案 | 验收标准 |
|---|---|---|---|
| T1 | 逻辑表格对象 | `document_table` 保存标题、表头、单位、脚注、页码范围和置信度 | 表格不再只以 Markdown 文本存在 |
| T2 | 行和单元格模型 | `document_table_row` 保存稳定行 ID、单元格 JSON、语义文本和来源 bbox | 每个数据行都能独立检索和引用 |
| T3 | 跨页续表识别 | 根据列签名、列数、数据类型、样式、首行内容和相邻页码建立候选 | 重复表头、无表头续页、行截断样例均能识别 |
| T4 | 续表合并 | 规则优先，低置信度候选由 VLM 校验；保留物理页码映射 | 合并后不丢物理来源，误合并可人工纠正 |
| T5 | 多层表头 | 把合并单元格和多行表头展开为规范化表头路径 | 每个单元格绑定完整列路径和单位 |
| T6 | 表头上下文传播 | Embedding、chunk、retrieval 和 prompt 都携带表标题、表头和单位 | 检索孤立数值时仍能看到其语义 |
| T7 | 上下文扩展 | 命中单元格后扩展同行、相邻行、表头、脚注和续表 | 表格问答不会只拿到一个裸数值 |
| T8 | 稳定分页协议 | API 使用 `tableId + rowStart + cursor`，响应携带表头和总行数 | 翻页不会切换成另一张表或丢失表头 |
| T9 | Agent 分页工具 | 提供表格分页读取、累计和去重工具 | 多页表格分析可跨上下文压缩保持连续 |
| T10 | 确定性计算 | 求和、筛选、排序、单位和空值处理使用代码执行 | 数值计算不依赖模型心算 |
| T11 | 前端续表展示 | 每页重复表标题和表头，显示续表标识 | 页面、导出和引用预览一致 |
| T12 | 表格评测 | 建立跨页、多表头、合并单元格、图表混合和分页连续性数据集 | 输出表头绑定、续接、行召回和引用准确率 |

### 5.3 知识图谱与 GraphRAG

| 编号 | 功能 | 技术方案 | 验收标准 |
|---|---|---|---|
| G1 | 领域本体 v1 | 定义有限实体、关系、事件和属性白名单 | 抽取结果可映射到稳定类型，未知类型进入候选区 |
| G2 | 实体与关系抽取 | 使用结构化输出模型，以文档元素和表格行为输入 | 抽取项必须绑定证据元素、页码和版本 |
| G3 | 实体消解 | 规范 ID、别名、向量相似度和灰区仲裁组合 | 可发现跨文档同义实体，并保留合并原因 |
| G4 | 证据边 | Neo4j 关系保存来源、置信度、抽取版本和证据 ID | 每条关系可回到原文证据 |
| G5 | 增量图谱 | 以文档版本为边界增量写入、替换和删除 | 文档重建后不残留旧关系 |
| G6 | 局部图检索 | 实体链接、一跳/两跳路径、关系类型过滤和邻域扩展 | 关系型问题返回可解释路径和原文证据 |
| G7 | 混合路由 | 规则/模型判断向量、关键词、图、表格和混合检索 | 每个决策进入 trace，可比较不同路由效果 |
| G8 | 全局社区摘要 | Neo4j GDS Leiden 形成社区，LLM 生成带证据的社区摘要 | 全局问题可总结多个社区且引用真实文档 |
| G9 | 图工具 | 提供实体、关系、路径和社区摘要工具 | Agent 只能调用参数化、受限的图查询 |
| G10 | 图权限 | 节点、关系、证据和图查询都绑定 tenant/kb 参数 | 跨租户或跨知识库数据无法被图查询召回 |
| G11 | 故障降级 | Neo4j 超时、不可用或熔断时回退文本检索 | 降级结果标记图检索未参与 |
| G12 | 图评测 | 覆盖实体准确率、链接准确率、路径召回、引用和 GraphRAG A/B | 报告图检索相对 Hybrid RAG 的收益与成本 |

### 5.4 推理模式

| 编号 | 功能 | 技术方案 | 验收标准 |
|---|---|---|---|
| R1 | 模型能力注册 | 配置声明推理档位、上下文、结构化输出和视觉能力 | 不支持的能力不会被请求 |
| R2 | 统一推理参数 | Provider Adapter 映射不同供应商的思考参数 | 请求日志可看到统一档位和实际供应商参数 |
| R3 | 动态路由 | 复杂度、风险、工具数、延迟和预算共同决策 | 路由原因可解释、可回放 |
| R4 | 推理预算 | 限制推理 Token、总 Token、耗时和费用 | 超预算时提前停止或降级 |
| R5 | 推理与答案分离 | SSE 分开输出状态、计划、工具进度和最终答案 | 普通视图不展示原始隐式思维内容 |
| R6 | 推理记录 | 保存推理摘要、实际档位、Token、耗时和结束原因 | 可按 run 和模型分析成本质量 |
| R7 | 降级策略 | 超时、限流、能力缺失和无预算时使用明确降级路径 | 用户能看到降级状态，不静默换模 |
| R8 | 档位评测 | 对同一数据集比较低/中/高档位的质量、延迟和费用 | 输出配置矩阵和推荐默认值 |

### 5.5 持久 Agent Runtime

| 编号 | 功能 | 技术方案 | 验收标准 |
|---|---|---|---|
| A1 | 会话事件日志 | `agent_session + agent_event` 追加写，事件序号单调递增 | 任意 run 可从事件重建请求和状态 |
| A2 | 检查点 | `agent_checkpoint` 保存事件游标、任务图、摘要和预算 | 进程重启后从最后完成步骤恢复 |
| A3 | 计划与任务图 | `agent_task` 保存目标、依赖、状态、负责人和产物 | Plan-Execute-Verify 有显式状态 |
| A4 | 动态转向 | 支持追加指令、暂停、恢复、取消和人工接管 | 运行中操作产生可审计事件 |
| A5 | 上下文治理 | 分区保存系统约束、计划、检索证据、工具结果和摘要 | 压缩后可恢复证据和未完成任务 |
| A6 | 工具契约 | JSON Schema、权限、超时、幂等、成本、敏感级别和审计 | 非法参数在模型调用后、执行前被拒绝 |
| A7 | MCP 2.0 | 官方 Java SDK、Streamable HTTP、资源、提示词和能力协商 | 工具发现、调用、鉴权、断线和版本协商通过集成测试 |
| A8 | 审批绑定 | 保存规范化操作摘要和哈希，执行前重新校验 | 已审批操作不可被替换参数后继续执行 |
| A9 | 工具沙箱 | 限制网络、文件、进程、CPU、内存、时间和输出大小 | 越界工具调用被拒绝并记录原因 |
| A10 | 产物管理 | 报告、表格、图片和差异文件登记为 Artifact | 产物可追踪到生成任务和证据 |
| A11 | 预算与终止 | 步数、工具数、深度、时间、Token 和费用统一预算 | 达到任一上限进入受控终止 |
| A12 | 任务调度 | PG 状态机 + RocketMQ + outbox，支持后台和长任务 | 提交、投递、消费和完成至少一次成功 |

### 5.6 多 Agent、记忆与 Skill

| 编号 | 功能 | 技术方案 | 验收标准 |
|---|---|---|---|
| X1 | Agent 注册表 | 定义角色、模型、工具、Skill、权限、预算和输出 Schema | Agent 配置可版本化和回放 |
| X2 | Coordinator-Worker | 主 Agent 拆分子任务并行分派 | Worker 上下文独立，任务图可见 |
| X3 | Reviewer | 检查证据、遗漏、冲突和输出契约 | Reviewer 不通过时回到指定任务重做 |
| X4 | 失败与取消 | 超时、重试、取消传播、死锁检测和部分成功 | 单个 Worker 失败不会拖垮全部任务 |
| X5 | 记忆作用域 | 区分会话、用户、知识库和组织记忆 | 记忆写入和检索都受租户与 ACL 控制 |
| X6 | 记忆治理 | 来源、置信度、TTL、冲突、删除和人工纠正 | 可追踪记忆来源并撤销错误记忆 |
| X7 | Skill 包 | SKILL.md、manifest、输入输出 Schema、示例和权限 | Skill 可渐进加载、版本化并禁止越权工具 |
| X8 | 编排预算 | 父任务给每个 Worker 分配 Token、工具和时间预算 | 整体费用和并发有硬上限 |
| X9 | 编排追踪 | OTel 父子 span 记录委派、等待、汇总和重试 | 可定位错误来自哪个 Agent |
| X10 | 编排评测 | 覆盖任务成功率、聚合质量、重复工作、冲突和成本 | 与单 Agent 基线比较并给出采用结论 |

### 5.7 企业治理与评测

| 编号 | 功能 | 技术方案 | 验收标准 |
|---|---|---|---|
| E1 | 租户与 ACL | 资源增加 tenant、org、owner、visibility 和权限对象 | 所有文档、表格、资产、图、记忆和评测入口统一校验 |
| E2 | 数据库隔离 | 关键表增加 tenant 维度，使用强制租户查询视图或 RLS，检索 SQL 和图查询强制过滤 | 应用层漏校验时数据库隔离仍阻止越权 |
| E3 | 模型网关 | 能力路由、限流、熔断、重试、供应商切换和成本 | 配额按租户、用户和 run 隔离 |
| E4 | 版本中心 | Prompt、Agent、模型、Skill、检索和图 Schema 版本化 | 线上运行记录完整版本组合 |
| E5 | 审计 | 记录输入、检索、工具、审批、执行、产物和删除 | 管理员可按主体、资源和 trace 查询 |
| E6 | 观测 | trace 包含路由、候选、表格分页、图路径、推理档位和预算 | 失败可从 trace 归因到具体组件 |
| E7 | 评测中心 | 复用现有评测表，增加解析、表格、图、Agent 和多 Agent 类型 | 每次发布可跑固定回归矩阵 |
| E8 | 安全 | 提示注入、敏感数据、工具外发、SSRF、压缩炸弹和文件校验 | 安全用例进入 CI 和发布门禁 |
| E9 | 数据生命周期 | 文档版本、索引版本、TTL、删除、归档和重建任务 | 删除后检索、图和记忆均不再命中 |
| E10 | 发布控制 | 功能开关、canary、灰度和回滚 | 新解析器、图检索和多 Agent 可独立开关 |

## 6. 核心数据契约

### 6.1 文档与表格

| 表 | 作用 | 关键字段 |
|---|---|---|
| `document_element` | 统一文档元素 | `element_id`、`document_id`、`version`、`parent_id`、`type`、`ordinal`、`page_from`、`page_to`、`bbox`、`text`、`metadata`、`parser_version` |
| `document_table` | 逻辑表格 | `table_id`、`document_id`、`version`、`caption`、`header_json`、`units`、`footnotes`、`page_from`、`page_to`、`continuation_of`、`next_table_id`、`confidence` |
| `document_table_row` | 表格行 | `row_id`、`table_id`、`row_index`、`cells_json`、`semantic_text`、`embedding`、`page_no`、`bbox` |
| `document_asset` | 图片、页面图和裁剪 | `asset_id`、`element_id`、`object_key`、`asset_type`、`checksum`、`page_no`、`bbox`、`ocr_text`、`vision_text` |
| `document_version` | 版本与处理轨迹 | `document_id`、`version`、`parser`、`parser_version`、`status`、`source_checksum`、`quality_report` |

约束：

- `document_chunk` 保持现有检索主表，增加 `content_type`、`element_ids`、`section_path`、`table_id`、`page_from`、`page_to` 和 `evidence_json`。
- 当前版本检索仍使用 `c.version = d.version`。
- 表格行向量与普通文本向量使用相同 Embedding 模型和维度。
- 所有元素、行和资产都带文档版本，版本切换和旧数据删除保持事务一致。

### 6.2 Agent

| 表 | 作用 |
|---|---|
| `agent_session` | 会话身份、租户、用户、状态和当前版本 |
| `agent_event` | 追加式事件日志，按 `session_id + seq` 唯一 |
| `agent_task` | 计划任务、依赖、状态、负责人和结果 |
| `agent_checkpoint` | 事件游标、消息摘要、任务图、预算和产物引用 |
| `agent_artifact` | 文件、报告、表格、图片和引用产物 |
| `agent_memory` | 记忆类型、作用域、来源、置信度、TTL 和向量 |
| `agent_skill` | Skill 版本、清单、权限和启用状态 |
| `agent_budget` | Token、工具、时长、费用和剩余额度 |
| `approval_record` | 操作摘要、摘要哈希、审批人、决定、有效期和使用记录 |

现有 `agent_run`、`agent_step` 和 `tool_call` 保留兼容查询，但新运行以事件日志和任务图为准。迁移阶段通过 run ID 建立关联。

### 6.3 图数据

Neo4j 节点：

- `Entity`
- `Document`
- `Element`
- `TableRow`
- `Community`

Neo4j 关系：

- `MENTIONS`
- `RELATED_TO`
- `DERIVED_FROM`
- `IN_COMMUNITY`
- `PART_OF`

所有节点和关系必须包含 `tenant_id`、`kb_id`、`version`、`confidence` 和 `source_version`。证据通过 `element_id`、`table_id`、`row_id` 或 `asset_id` 回指 PostgreSQL。

## 7. API 与交互契约

| 能力 | API 方向 |
|---|---|
| 文档元素 | `GET /api/documents/{id}/elements?version=&cursor=` |
| 表格分页 | `GET /api/tables/{tableId}/rows?cursor=&limit=` |
| 资产预览 | `GET /api/assets/{assetId}` |
| 图查询 | `POST /api/graph/search`，只接受类型化请求 |
| Agent 事件 | `GET /api/agent/sessions/{id}/events?afterSeq=` |
| Agent 恢复 | `POST /api/agent/runs/{id}/resume` |
| Agent 转向 | `POST /api/agent/runs/{id}/steer` |
| 记忆管理 | 列出、纠正、删除和过期记忆 |
| Skill 管理 | 列出、启停、版本和权限检查 |
| 评测矩阵 | 按解析器、检索、推理、图和 Agent 配置运行 |

分页响应必须包含：

- `tableId`
- `caption`
- `header`
- `rowStart`
- `rowEnd`
- `totalRows`
- `continuationCursor`
- `continuedFrom`
- `continuedTo`

## 8. 实施阶段

### Phase 0：基线与契约

交付：

- 冻结文档元素、表格、证据、Agent 事件和图数据契约。
- 建立多模态、跨页表格、GraphRAG、推理档位和 Agent 轨迹五类 Gold Set。
- 为现有 Hybrid RAG、文本 Agent 和推理配置记录基线。
- 建立数据库迁移、版本和功能开关方案。

退出条件：

- 数据契约评审通过。
- 每类评测集具备稳定 ID、预期证据和评分规则。
- 基线报告可重复运行。

### Phase 1：文档智能 sidecar

交付：

- `docling-serve` 容器与健康检查。
- 多格式上传、解析适配、资产落 MinIO 和错误状态。
- Docling JSON 到内部元素、表格和资产模型的映射。
- PDFBox 快速路径与 sidecar 故障降级。

退出条件：

- 所有支持格式至少有一条真实样例。
- 解析结果具备完整页码和区域证据。
- 重复消息和失败重试不产生重复数据。

当前进度：

- 已落地 Docling HTTP 客户端、DoclingDocument 元素/表格/资产映射、多格式上传白名单和 PDFBox 降级。
- 已新增文档元素、逻辑表格、表格行和资产版本化表及持久化仓储。
- 已新增 Docling Serve CPU sidecar Compose 服务、本机端口绑定、健康检查和独立 `docling` profile。
- 待完成真实多格式样例验收、图片二进制资产入库和端到端故障演练。

### Phase 2：表格与分页完整性

交付：

- 逻辑表格、行级语义文本和多层表头。
- 跨页续表识别、合并、置信度和人工纠正入口。
- 行级 Embedding、上下文扩展和表格分页 API。
- Agent 表格分页读取与确定性计算工具。

退出条件：

- 跨页 Gold Set 上的表格续接和表头绑定达到预注册门槛。
- 命中任意一行都能恢复表标题、表头和页码。
- 分页读取前后行集合无重复、无遗漏。

当前进度：

- 已落地逻辑表格、行级语义文本、表头 JSON 和文档版本化持久化。
- 已实现同表头、连续页码的跨页表格合并，合并行保留各自物理页码和 bbox。
- 已提供受文档 owner 权限保护的表格行分页 API，响应固定携带表标题、表头和页码范围，并使用稳定 `row:N` 游标。
- 已生成表格行独立 Embedding chunk，每个 chunk 携带表标题、完整表头、行语义、页码以及 `tableId/rowId` 元数据。
- 已将 chunk 的文档、版本、表格、行和页码元数据贯穿检索结果与 Agent 引用。
- 已在表格行命中时扩展上下相邻行、表标题、完整表头和页码范围，Agent 引用使用扩展后的上下文。
- 已增加只读 Agent 工具 `read_table_rows`，支持版本、稳定游标和每页表头上下文，并在后台执行前重新校验文档权限。
- 待完成人工纠正入口和跨页 Gold Set 验收。

### Phase 3：多模态检索与证据链

交付：

- OCR、视觉描述、表格行和普通文本的统一检索视图。
- 图片、图表和扫描件的查询路由。
- 页面 bbox、资产和元素级引用。
- 前端引用预览与区域高亮。

退出条件：

- 文本、扫描件、图片和图表查询都有端到端样例。
- 引用能打开原始资产并定位页码和区域。
- 多模态配置与纯文本 Hybrid RAG 完成 A/B。

当前进度：

- 已提供文档元素分页 API，返回元素类型、父子关系、页码和 bbox，支持稳定 `element:N` 游标。
- 表格行和 Agent 引用已携带文档、版本、表格、行和页码元数据。
- Docling 内嵌图片会写入 MinIO，并可通过受文档权限保护的 presigned URL 获取图片、页码和 bbox。
- 待完成 Vision 模型调用、多模态查询路由、区域高亮和端到端样例。

### Phase 4：持久 Agent 与推理模式

交付：

- Agent 会话事件、任务图、检查点、恢复和转向。
- Plan-Execute-Verify 与统一预算。
- 推理档位能力注册、动态路由、降级和成本评测。
- 工具审批摘要绑定和 MCP SDK 2.0 接入。

退出条件：

- 进程崩溃后可从检查点恢复。
- 审批后替换工具参数会被拒绝。
- 同一任务可比较不同推理档位的质量、延迟和费用。

当前进度：

- 已增加 `RAG2AGENT_REASONING_EFFORT=off|auto|minimal|low|medium|high|max` 配置。
- `auto` 根据问题长度和跨文档/比较/推理等标记选择档位，非 `off` 档位通过 `reasoning_effort` 请求参数传给兼容供应商。
- 推理档位已写入 LLM 耗时指标标签和 Agent 步骤输出；默认 `off`，保持现有供应商兼容性。
- 已新增 `agent_session`、`agent_event` 和 `agent_checkpoint` 表，现有 SSE 事件同步写入不可变事件日志。
- Agent 结束或失败时写入终端检查点；Redis Stream 继续只承担实时投递。
- 已提供按用户和会话读取持久事件的分页 API，Redis Stream 过期后仍可从 PostgreSQL 回放轨迹。
- 审批工具调用会保存规范化操作摘要，批准后执行前重新计算摘要并比对，操作参数变更会被拒绝。
- 待完成模型能力协商、推理摘要/Tokens 解析、预算降级和档位 A/B 评测。
- 待完成事件回放恢复、任务图、暂停/转向和检查点时间旅行。

### Phase 5：知识图谱与 GraphRAG

交付：

- 实体/关系抽取、证据边和实体消解。
- 增量建图、图查询工具、路由和故障降级。
- Neo4j GDS 社区发现、社区摘要和证据回指。
- 图权限过滤和图评测。

退出条件：

- 关系型和多跳问题可返回可解释路径。
- 全局问题可命中社区摘要并引用原始证据。
- 图检索关闭或故障时系统自动回退。

当前进度：

- 已接入 Neo4j Java Driver 6.1，并增加默认关闭的图能力配置。
- 已实现按知识库隔离的文档、实体、证据边和关系边写入仓储，以及 1~3 跳受限路径查询。
- 图写入使用参数化 Cypher、`MERGE` 和文档级替换，查询限制跳数与返回行数。
- 已实现模型实体/关系抽取，过滤无文档元素证据的输出，并在入库时自动建图；图服务失败只降级图索引。
- 已增加只读 Agent 图工具 `search_knowledge_graph`，关闭或不可用时明确返回文本检索降级标记。
- 待完成混合检索路由、GDS 社区摘要和真实 Neo4j 集成验收。

### Phase 6：多 Agent、记忆与 Skill

交付：

- Coordinator-Worker-Reviewer 编排。
- 任务图、预算、取消传播、死锁检测和聚合。
- 长期记忆、情景记忆和 Skill 注册。
- 多 Agent 轨迹与单 Agent 基线对比。

退出条件：

- 至少一个真实任务上多 Agent 的质量或耗时优于单 Agent。
- 单个 Worker 失败可隔离或重试。
- 记忆与 Skill 均受租户、权限和版本约束。

当前进度：

- 已新增 PostgreSQL + pgvector 长期记忆表，支持用户/知识库作用域、来源、置信度和 TTL。
- 已提供 `search_agent_memory` 只读工具和需要人工审批的 `save_agent_memory` 写入工具。
- 记忆检索强制按用户过滤，知识库记忆在工具入口校验 owner；已提供记忆列表和删除 API。
- 已新增持久 Agent 任务图和 `FOR UPDATE SKIP LOCKED` ready-task 抢占，支持依赖、完成/失败状态和任务查询 API。
- 已实现受控 Coordinator → Retriever → Analyst → Reviewer 顺序执行器，每个 Worker 使用独立提示词与上下文，Reviewer 独立复核最终答案。
- 待完成并行 Worker、预算分配、记忆冲突处理和 Skill 注册。

### Phase 7：企业治理与发布

交付：

- 租户、组织、文档、表格、资产、记忆和图统一权限。
- 模型网关、版本中心、配置灰度、审计和删除闭环。
- 解析、表格、图、推理、Agent 和多 Agent 的回归矩阵。
- 故障演练、容量基线、告警和回滚。

退出条件：

- 所有关键入口有越权测试。
- 任一版本组合可追踪、回放和回滚。
- 安全、恢复、成本和核心质量指标进入发布门禁。

## 9. 依赖关系

```text
Phase 0
  -> Phase 1
      -> Phase 2
      -> Phase 3
Phase 3
  -> Phase 4
Phase 1 + Phase 4
  -> Phase 5
Phase 4 + Phase 5
  -> Phase 6
Phase 0..6
  -> Phase 7
```

- Phase 2 和 Phase 3 可以并行，但共享文档元素与资产契约。
- Phase 5 的图抽取依赖稳定的元素、表格行和证据 ID。
- Phase 6 依赖持久 Runtime、预算、工具权限和图/表检索。
- Phase 7 的评测门禁随各阶段持续建设，在最终阶段统一收口。

## 10. 评测与验收指标

### 10.1 文档与表格

- 页面覆盖率、元素映射率、资产完整率。
- OCR 字符错误率。
- 表格结构准确率、单元格召回率。
- 跨页续表识别精确率、召回率和表头绑定准确率。
- 行级引用准确率、页码和 bbox 准确率。
- 重复入库、失败重试和版本切换的一致性。

### 10.2 检索与图

- Hit@K、MRR、Evidence Precision。
- 文本、扫描件、图表和表格分类召回。
- 实体抽取精确率/召回率、实体链接准确率。
- 多跳路径召回和路径引用准确率。
- 全局社区总结的事实性和证据覆盖率。
- GraphRAG、Hybrid RAG 和纯文本配置的质量、延迟、成本对比。

### 10.3 Agent 与推理

- 任务成功率、计划遵循率、最终答案正确率。
- 工具选择准确率、参数正确率、重复调用率。
- 平均步数、P95 延迟、Token 和费用。
- 中断恢复成功率、审批绑定通过率、越权拒绝率。
- 各推理档位的质量、成本、延迟和降级率。
- 多 Agent 相对单 Agent 的质量增益、重复工作率和协调开销。

### 10.4 发布门禁

- 数据契约和数据库迁移测试通过。
- 所有新入口具备租户和 ACL 测试。
- 审批、恢复、幂等、删除和副作用测试通过。
- 每类 Gold Set 不出现相对基线的关键指标回退。
- 新解析器、图检索、推理路由和多 Agent 都能独立关闭。

## 11. 风险与控制

| 风险 | 控制方式 |
|---|---|
| Docling 模型较重、冷启动慢 | sidecar 独立资源池、固定镜像、预热、健康检查、并发上限和 PDFBox 快速路径 |
| OCR/VLM 产生错误内容 | 保存原始证据、置信度、模型版本和区域；低置信度进入复核 |
| 跨页表格误合并 | 规则候选、VLM 仅做灰区校验、保留物理页码、人工纠正和版本回滚 |
| 图片描述污染检索 | 描述绑定资产和区域，区分 OCR、视觉描述与原始文本来源 |
| GraphRAG 成本和幻觉 | 只在路由命中时启用，限制跳数和返回量，社区摘要强制回指证据 |
| 图数据陈旧 | 以文档版本为边界增量替换，失败不切换新版本 |
| Agent 事件日志膨胀 | 检查点 + 快照 + 工具结果外置 + 保留策略 |
| 多 Agent 死锁或成本失控 | 单拓扑、预算分配、最大深度、取消传播、循环检测和 Reviewer 终止 |
| 记忆投毒 | 受控写入、来源证据、租户命名空间、TTL、冲突检测和人工撤销 |
| 租户过滤遗漏 | 应用双层校验、SQL/图查询强制参数、越权测试和默认拒绝 |
| 模型能力漂移 | 能力注册、版本冻结、降级路径和推理档位回归矩阵 |

## 12. 实施前检查

进入实现前需要完成：

- 评审并冻结本文的数据契约和阶段顺序。
- 准备合法可用的中文测试文档、扫描件、复杂表格和图谱问题集。
- 确认 Vision、Chat、Embedding 和 Rerank 的可用供应商及预算。
- 确认 Docling、Neo4j GDS、MCP Java SDK 和模型适配器的版本组合。
- 生成数据库迁移清单和兼容策略。
- 为每个 Gold Set 预注册阈值、运行入口和报告格式。

## 13. 选型依据

- [Docling](https://github.com/docling-project/docling)：多格式文档、版面、表格、OCR、DoclingDocument JSON 和本地部署。
- [Docling Serve](https://github.com/docling-project/docling-serve)：文档转换 HTTP 服务和容器部署。
- [PaddleOCR PP-StructureV3](https://github.com/PaddlePaddle/PaddleOCR)：中文版面、表格、公式和文档解析对照基线。
- [Neo4j Graph Data Science](https://neo4j.com/docs/getting-started/gds/)：图投影、社区发现和路径算法。
- [MCP Java SDK](https://java.sdk.modelcontextprotocol.io/latest-snapshot/)：Streamable HTTP、工具、资源、提示词和协议协商。
- [OpenAI Agents SDK](https://openai.github.io/openai-agents-python/)：Sessions、Guardrails、RunState、Handoff 和 Tracing 契约参考。
- [Microsoft Agent Framework](https://learn.microsoft.com/agent-framework/overview/)：Harness、Workflow、Checkpoint 和 telemetry 设计参考。
- [Anthropic Agent Workflows](https://www.anthropic.com/engineering/building-effective-agents)：顺序、并行和 Evaluator-Optimizer 编排模式。
- [D-RAC](https://arxiv.org/abs/2609.24220)：文档解析、行级表格语义和检索感知切块参考。
