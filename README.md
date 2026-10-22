# RAG2Agent

> 企业级多模态 RAG + 可控 Agent 平台。基于 Java 21 + Spring Boot 3.5 的多模块后端与 Vue 3 前端，跑通「多格式文档解析 → 异步入库 → 表格/图片语义索引 → 向量 + 关键词 + GraphRAG 混合检索 → 带证据回答 → 工具审批 → 并行多 Agent → Gold Set 评测」完整闭环。

![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.7-6DB33F?logo=spring&logoColor=white)
![Vue](https://img.shields.io/badge/Vue-3-4FC08D?logo=vuedotjs&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)
![Neo4j](https://img.shields.io/badge/Neo4j%20GDS-2.13-4581C3?logo=neo4j&logoColor=white)
![MCP](https://img.shields.io/badge/MCP-Java%20SDK%202.0-6B57FF)

当前版本为 [V3 企业验收版](https://github.com/cxzcanying/Rag2Agent/releases/tag/v3)。项目依赖 PostgreSQL、Redis、MinIO、RocketMQ、Neo4j、Docling、Jaeger 等本地中间件，完整拓扑通过 Docker Compose 启动。V3 已完成多模态文档、跨页表格、局部/全局 GraphRAG、MCP 2.0、持久 Agent Runtime、并行多 Agent、tenant/org/kb ACL 与五类 Gold Set 的端到端验收，详见 [V3 Docker 验收报告](docs/v3-docker-acceptance-report.md)。

## 目录

- [功能特性](#功能特性)
- [项目亮点](#项目亮点)
- [技术栈](#技术栈)
- [架构与模块](#架构与模块)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [API 概览](#api-概览)
- [异步评测](#异步评测)
- [测试与验证](#测试与验证)
- [CI](#ci)
- [项目结构](#项目结构)
- [文档](#文档)
- [边界与路线图](#边界与路线图)
- [参与贡献](#参与贡献)

## 功能特性

| 领域 | 已实现 |
| --- | --- |
| 账号与权限 | 注册、登录、Sa-Token 会话；tenant/org/knowledge-base ACL；`PRIVATE/ORGANIZATION/TENANT` 可见性和 `VIEWER/EDITOR/ADMIN` 成员角色；文档、表格、资产、图谱、记忆、评测入口统一校验 |
| 多模态入库 | 文件上传 MinIO，RocketMQ 异步处理；Docling 解析 PDF、DOCX、PPTX、XLSX、HTML、图片和邮件，递归映射嵌套正文、表格、图片与 bbox；持久化元素、逻辑表格、行级语义文本和资产；支持跨页续表、重复表头、稳定行游标与页码证据 |
| 图片理解 | Qwen3-VL provider 可配置；图片语义描述进入普通检索；Vision 不可用时自动降级 Docling OCR；图片资产通过权限受控的 presigned URL 提供 |
| 混合检索 | Query Routing；pgvector 向量、`zhparser/pg_trgm` 中文关键词、Neo4j 图路径三路并行召回；RRF 融合和 Rerank；表格命中自动扩展表标题、完整表头、相邻行和页码 |
| GraphRAG | 实体/关系/证据元素抽取并写入 Neo4j；1-3 跳受限路径查询进入混合检索；GDS Leiden 社区发现、社区摘要、局部/全局图工具；图分支独立限额与故障降级 |
| Agent Runtime | OpenAI-compatible function calling；推理档位与动态路由；Session/Event/Checkpoint、上下文压缩、长期记忆、工具审批摘要、SSE 回放、请求幂等、预算、取消传播和任务依赖阻塞 |
| 多 Agent | Coordinator → 并行 Analyst → Reviewer；Worker 独立上下文以及 Token、工具和时长预算；单 Worker 失败隔离并返回 `DEGRADED`，Reviewer 只依赖成功任务 |
| MCP | 官方 MCP Java SDK 2.0 Streamable HTTP；协议版本协商、工具、资源、提示词、会话恢复；Bearer + scope 鉴权；未声明只读的远程工具默认审批 |
| AI 可靠性 | 超时、重试、熔断、降级、并发舱壁；Redis Lua 令牌桶用户限流；失败响应统一脱敏 |
| 缓存 | 查询向量与正文切片共用 Caffeine L1 + Redis L2 片段级缓存（批量 `getOrComputeBatch`），single-flight + 内容寻址 key（含 provider/model/维度/模型版本）复用未变切片，未命中才批量回源 |
| 可观测性 | Micrometer、Prometheus、OpenTelemetry OTLP、Jaeger、JSON 日志；HTTP/检索/LLM/工具/MQ trace context；Token、成本、预算和线程池指标 |
| 评测 | 异步 run、逐题结果、取消和恢复；`RAG/TABLE/MULTIMODAL/GRAPH/AGENT` 五类 Gold Set、证据契约、覆盖率门禁；Hit@k、MRR、Faithfulness、Answer Correctness |
| 前端 | Vue 3 + Vite；登录、文档、对话审批、评测进度、知识库成员/可见性管理、Gold Set 覆盖率门禁 |

## 项目亮点

V3 的重点是把“能检索、能对话”推进到“证据完整、流程可控、失败可恢复、权限可验证”。所有关键能力均有代码、单测或 Docker 端到端证据：

| 维度 | V3 已落地能力 | 验证结果 |
| --- | --- | --- |
| 多模态与表格 | Docling 解析、嵌套元素、逻辑表格、续表合并、图片 OCR/Vision、资产证据 | PDF/HTML/PNG 和真实双页 PDF 验收；7 行续表合并为 1 张表，页码 `1-2` |
| 混合检索 | 向量 + 中文关键词 + 图路径并行，RRF + Rerank；图召回限额和表格上下文扩展 | 表格查询可恢复表标题、完整表头和相邻行；GraphRAG 查询返回可解释路径 |
| GraphRAG | 文档实体/关系/证据边、路径查询、GDS 社区发现与摘要 | Neo4j GDS `2.13.12`；社区刷新生成 6 个主题摘要 |
| Agent Runtime | 推理路由、持久事件/检查点、工具审批、幂等、预算、记忆、取消传播 | 审批摘要绑定、取消级联、依赖阻塞和事件回放均有测试 |
| 多 Agent | Retriever → N Analyst → Reviewer，独立上下文和 Worker 预算 | 真实并行研究任务返回 `COMPLETED`，单 Worker 失败可降级 |
| 企业权限 | tenant/org/kb ACL、可见性、成员角色、所有子资源继承边界 | 跨用户授权前不可见、授权后只读、`VIEWER` 写操作被拒绝 |
| MCP | 官方 Java SDK 2.0 Streamable HTTP、工具/资源/提示词/能力协商 | 官方客户端完成 initialize、发现、调用；Bearer/scope 鉴权通过 |
| 发布门禁 | 五类 Gold Set 证据契约、覆盖率和违规项 | 五类各 1 条验收用例，`valid=true`、缺失 0、违规 0 |
| 工程验证 | Docker 全链路、Maven reactor、Vite build | 8 个服务 healthy；140/140 测试通过；前端生产构建通过 |

验收环境、指标、问题修复和边界见 [V3 Docker 验收报告](docs/v3-docker-acceptance-report.md)。当前版本仍是单机工程级部署：数据库 RLS、全链路容量压测、K8s/Helm、正式 OAuth 身份系统和真实业务 Gold Set 属于后续生产化范围。

## 技术栈

| 分类 | 技术 | 版本 / 说明 |
| --- | --- | --- |
| 后端语言 | Java | 21 |
| 后端框架 | Spring Boot | 3.5.7（Maven 多模块） |
| ORM | MyBatis-Plus | 3.5.14 |
| 鉴权 | Sa-Token | 1.44.0 |
| API 文档 | springdoc-openapi | 2.8.14 |
| HTTP 客户端 | OkHttp | 4.12.0 |
| 前端 | Vue 3 + Vite | Vue 3.5.22 / Vite 7.1.12 |
| UI 组件 | Element Plus | 2.14.4 |
| 数据库 | PostgreSQL | 17（+ pgvector / pg_trgm，可选 zhparser） |
| 缓存 / 限流 | Redis | 7.4（Alpine） |
| 对象存储 | MinIO | 2025-09-07 release |
| 消息队列 | RocketMQ | 5.3.2 |
| 图数据库 | Neo4j + GDS | Neo4j 5 / GDS 2.13 |
| 文档智能 | Docling Serve | 1.36（CPU sidecar） |
| Vision | Qwen3-VL | 可配置 OpenAI-compatible provider |
| 工具协议 | MCP Java SDK | 2.0 Streamable HTTP |
| 链路追踪 | Jaeger | 1.60（all-in-one） |
| 覆盖率 / 校验 | Spotless | 2.46.1（代码格式校验） |

`infra-ai` 提供 Chat、Embedding、Rerank、Vision 和文档智能抽象，当前配置 DeepSeek（chat）、SiliconFlow（embedding / rerank / 定价）、Qwen3-VL（vision）及 Docling。

## 架构与模块

多模块 Maven 工程，各模块职责如下：

| 模块 | 职责 |
| --- | --- |
| `framework` | 通用响应、错误处理、Redis、鉴权和持久化基础设施 |
| `infra-ai` | 按 capability 选择 active provider；Chat、Embedding、Rerank、Vision、DocumentIntelligence 接口和客户端 |
| `rag-core` | Docling 映射、切块、检索、RRF、重排、StructuredDocument 和 Prompt 相关核心能力 |
| `mcp-server` | MCP Java SDK 2.0 Streamable HTTP、工具/资源/提示词、Bearer/scope 鉴权 |
| `bootstrap` | Spring Boot 启动、持久 Agent Runtime、多 Agent、GraphRAG、ACL、评测、MQ 消费和数据库访问 |
| `web` | Vue 3 前端 |

依赖主线：`framework` / `infra-ai` 为底座，`rag-core` 依赖 `infra-ai`，`bootstrap` 聚合全部并对外提供服务，`web` 调用 `bootstrap`。完整选型、演进路线与模块边界参见 [docs/tech-selection.md](docs/tech-selection.md)。

## 快速开始

### 前置条件

- Windows + Docker Desktop
- JDK 21 与 Maven 3.9+
- Node.js 20+（仅运行前端时需要）
- DeepSeek Chat key；SiliconFlow key 用于 BGE-M3 Embedding 和 Rerank

### 后端与中间件

在项目根目录执行：

```powershell
Copy-Item -LiteralPath .env.example -Destination .env
# 编辑 .env，至少填写 DEEPSEEK_API_KEY 和 SILICONFLOW_API_KEY
# 要验证 Vision，再填写 DASHSCOPE_API_KEY 并设置 RAG2AGENT_QWEN_VL_ENABLED=true

# 中文关键词完整能力：构建带 zhparser 的 PostgreSQL 镜像
docker build -t rag2agent-postgres:pg17-zhparser docker/postgres
$env:RAG2AGENT_POSTGRES_IMAGE="rag2agent-postgres:pg17-zhparser"

# 完整拓扑：PostgreSQL、Redis、MinIO、Docling、RocketMQ、Neo4j、Jaeger
docker compose --profile full up -d

# 启动后端并启用完整能力
$env:RAG2AGENT_MQ_ENABLED="true"
$env:RAG2AGENT_DOCLING_ENABLED="true"
$env:RAG2AGENT_NEO4J_ENABLED="true"
mvn -pl bootstrap spring-boot:run
```

`application.yml` 默认使用 `dev` profile，连接宿主机映射端口。首次创建数据卷时会自动执行 `docker/postgres/init/001-015`；复用旧数据卷时必须手动按顺序补跑未执行脚本，直到 [015_gold_set.sql](docker/postgres/init/015_gold_set.sql)。初始化脚本只会在新卷首次启动时自动执行。

中文关键词检索默认使用 `RAG2AGENT_CHINESE_SEARCH_MODE=auto`：检测到 `zhparser` 时走 PostgreSQL `tsvector`，否则回退二元切分。要启用 PG17 的 `zhparser` 镜像：

```powershell
docker build -t rag2agent-postgres:pg17-zhparser docker/postgres
$env:RAG2AGENT_POSTGRES_IMAGE="rag2agent-postgres:pg17-zhparser"
docker compose --profile full up -d
```

`zhparser` 是中文关键词能力的推荐路径；未启用时服务会按配置回退到二元切分。

### 前端

另开终端执行：

```powershell
Set-Location -LiteralPath .\web
npm install
npm run dev
```

Vite 开发服务器会将 `/api` 代理到 `http://localhost:18080`。启动后打开终端输出的前端地址即可。

### 端口

| 服务 | 宿主端口 | 用途 |
| --- | ---: | --- |
| Spring Boot | 18080 | 后端 API |
| PostgreSQL | 15432 | 数据库 |
| Redis | 16379 | 缓存、限流和 Agent 上下文 |
| MinIO | 9000 / 9001 | 对象存储 / 控制台 |
| Docling Serve | 15001 | 文档解析 sidecar |
| RocketMQ | 19876、19091、19111、19112 | NameServer、Broker 通信 |
| Neo4j | 17474 / 17687 | Browser / Bolt；GraphRAG 与 GDS |
| Jaeger | 16686、4317、4318 | UI / OTLP gRPC / OTLP HTTP |

项目刻意使用高位端口，避免 Windows 动态保留端口导致「端口占用但查不到进程」。端口与中间件注意事项见 [docs/pitfalls-and-verification.md](docs/pitfalls-and-verification.md)。

Compose 服务健康检查：

```powershell
pwsh ./scripts/compose-health.ps1       # Demo profile
pwsh ./scripts/compose-health.ps1 -Full # 包含 RocketMQ、Neo4j、Jaeger
```

## 配置说明

`.env` 已被 `.gitignore` 忽略，禁止把真实密钥提交到 Git。配置由 `DotenvEnvironmentPostProcessor` 从项目根目录向上查找 `.env`，系统环境变量优先。

常用变量：

```text
DEEPSEEK_API_KEY=...
DEEPSEEK_MODEL=deepseek-v4-flash
DEEPSEEK_MODEL_PRO=deepseek-v4-pro
SILICONFLOW_API_KEY=...
SILICONFLOW_EMBEDDING_MODEL=BAAI/bge-m3
SILICONFLOW_RERANK_MODEL=BAAI/bge-reranker-v2-m3
RAG2AGENT_ACTIVE_CHAT_PROVIDER=deepseek
RAG2AGENT_ACTIVE_EMBEDDING_PROVIDER=siliconflow
RAG2AGENT_ACTIVE_RERANK_PROVIDER=siliconflow
RAG2AGENT_TOOL_TIMEOUT_MILLIS=10000
ROCKETMQ_NAMESRV=localhost:19876
RAG2AGENT_EMBEDDING_CACHE_ENABLED=true
RAG2AGENT_RATE_LIMIT=60
RAG2AGENT_DOCLING_ENABLED=false
RAG2AGENT_DOCLING_BASE_URL=http://localhost:15001
RAG2AGENT_REASONING_EFFORT=off
RAG2AGENT_NEO4J_ENABLED=false
RAG2AGENT_NEO4J_URI=bolt://localhost:17687
RAG2AGENT_QWEN_VL_ENABLED=false
QWEN_VL_MODEL=qwen3-vl-flash
DASHSCOPE_API_KEY=
RAG2AGENT_TEAM_ANALYST_WORKERS=2
RAG2AGENT_TEAM_WORKER_TOKEN_BUDGET=12000
RAG2AGENT_TEAM_WORKER_TOOL_BUDGET=2
RAG2AGENT_TEAM_WORKER_TIMEOUT_MILLIS=60000
RAG2AGENT_DEFAULT_TENANT_ID=1
RAG2AGENT_DEFAULT_ORGANIZATION_ID=1
```

`RAG2AGENT_MQ_ENABLED`、`RAG2AGENT_CHINESE_SEARCH_MODE`、`RAG2AGENT_EMBEDDING_*`、`RAG2AGENT_RATE_LIMIT_*`、`RAG2AGENT_AI_RESILIENCE_*`、`RAG2AGENT_MCP_REMOTE_*`、`RAG2AGENT_DOCLING_*`、`RAG2AGENT_TEAM_*` 等均可在 `.env` 或环境变量中覆盖，默认值见 `bootstrap/src/main/resources/application.yml`。价格相关变量以 `DEEPSEEK_*_PRICE_*` / `SILICONFLOW_*_PRICE_*` 前缀为主，默认使用官方分时 / 免费用量口径。

Docling 默认关闭；执行 `docker compose --profile docling up -d` 后开启 `RAG2AGENT_DOCLING_ENABLED=true`，即可解析非 PDF 文档、扫描件并提供表格结构。sidecar 仅绑定本机 `15001` 端口，解析结果中的元素、表格、表格行和资产会随文档版本写入 `009_document_intelligence.sql` 创建的结构表。

接口限流为惰性令牌桶：`RAG2AGENT_RATE_LIMIT` 作为桶容量，`RAG2AGENT_RATE_LIMIT_WINDOW_SECONDS` 作为补充窗口，实际每秒补速率 = 容量 / 窗口秒（默认 60 / 60 = 1 个/秒，允许受控突发）；登录失败锁定仍为固定窗口计数。

Embedding 缓存 key 使用 provider、model、维度、模型版本和文本 hash 做内容寻址，查询向量与正文切片（片段级）共用同一套缓存，不包含文档版本号，因此同一文本可跨文档版本复用；重复入库时未变的切片直接复用，未命中的才回源批量计算。模型版本变更时应调整 model 配置或提升 `RAG2AGENT_EMBEDDING_MODEL_VERSION` 使新旧 key 隔离，并注意存量切片的库内向量仍需 `reingest` 重建，不能把旧模型结果当作新模型结果。

## API 概览

除健康检查、版本和 provider 查询外，业务接口需要登录后的 Sa-Token；

```text
POST /api/auth/register
POST /api/auth/login
GET  /api/auth/me

POST /api/knowledge-bases
GET  /api/knowledge-bases
PATCH /api/knowledge-bases/{kbId}/visibility
GET  /api/knowledge-bases/{kbId}/members
POST /api/knowledge-bases/{kbId}/members
DELETE /api/knowledge-bases/{kbId}/members/{userId}
POST /api/documents/upload
GET  /api/documents?kbId={kbId}
GET  /api/documents/{id}/presign
POST /api/documents/{id}/reingest
GET  /api/documents/{documentId}/elements?version={version}&cursor={cursor}&limit={limit}
GET  /api/documents/{documentId}/tables/{tableId}/rows?version={version}&cursor={cursor}&limit={limit}
GET  /api/documents/{documentId}/assets/{assetId}?version={version}
POST /api/documents/{documentId}/assets/{assetId}/describe?version={version}
GET  /api/search?kbId={kbId}&query={query}&topK={topK}
POST /api/search/multimodal

POST /api/chat                         # SSE
POST /api/agent/approvals/{runId}
POST /api/agent/runs/{runId}/cancel
GET  /api/agent/sessions/{sessionId}/events?afterSeq={seq}&limit={limit}
GET  /api/agent/runs/{runId}/tasks
GET  /api/agent/memories?kbId={kbId}&limit={limit}
DELETE /api/agent/memories/{memoryId}
POST /api/agent/teams/research
POST /api/knowledge-bases/{kbId}/graph/communities/refresh
GET  /api/knowledge-bases/{kbId}/graph/communities?limit={limit}

POST /api/evaluations/cases/import
POST /api/evaluations/runs
POST /api/evaluations/matrix
GET  /api/evaluations/gold-set/coverage?kbId={kbId}
GET  /api/evaluations/runs?kbId={kbId}
GET  /api/evaluations/runs/{runId}
GET  /api/evaluations/runs/{runId}/results
POST /api/evaluations/runs/{runId}/cancel

GET  /api/health
GET  /api/version
GET  /api/ai/providers
GET  /actuator/health
GET  /actuator/prometheus
```

Swagger UI：`http://localhost:18080/swagger-ui.html`；OpenAPI JSON：`http://localhost:18080/v3/api-docs`。

## 异步评测

评测提交接口不会等待全部用例完成，而是立即返回 `runId`：

```http
POST /api/evaluations/runs
Idempotency-Key: 8d8b4e1e-9f6f-4fc3-a8ba-2e5b0a4fbe31
Content-Type: application/json
```

```json
{
  "kbId": 1,
  "name": "向量基线",
  "config": {
    "strategy": "VECTOR",
    "topK": 5,
    "candidateTopK": 20,
    "rrfK": 60,
    "rerankEnabled": true,
    "evaluateGeneration": false,
    "timeoutSeconds": 3600
  }
}
```

后台任务按 `QUEUED → RUNNING → COMPLETED/FAILED/CANCELLED/TIMEOUT` 更新数据库。客户端轮询 `GET /runs/{runId}` 获取状态、完成数、指标和错误信息，再按需读取 `/results` 的逐题结果。客户端断开不会停止后台任务；应用重启后会从 `eval_run` 中恢复排队/运行中的任务，已完成的 `eval_case_result` 不会重复执行。

`eval_run` 和 `eval_case_result` 是评测的最终事实源，不依赖 `run-state.json`。同一知识库下相同 `Idempotency-Key` 的并发提交只创建一个 run，重复请求返回同一 run；同一 key 搭配不同参数会稳定返回 400。矩阵提交会为子任务派生稳定幂等键。

导入用例格式：

```json
{
  "kbId": 1,
  "cases": [
    {
      "question": "如何申请年假？",
      "expectedAnswer": "按制度提交年假申请。",
      "goldenDocumentIds": [12],
      "caseType": "TABLE",
      "evidence": {
        "tableId": "tables-0",
        "headers": ["职级", "年假天数"],
        "rowIds": ["2"]
      }
    }
  ]
}
```

`caseType` 支持 `RAG/TABLE/MULTIMODAL/GRAPH/AGENT`。导入时会校验各类证据字段；`GET /api/evaluations/gold-set/coverage?kbId={kbId}` 返回五类计数、缺失类别和违规项，`valid=true` 才通过发布门禁。

`evaluateGeneration=true` 会额外调用回答模型和裁判模型，耗时及额度明显增加，建议使用异步查询而不是等待 HTTP 请求超时。

## 测试与验证

后端业务测试（必须带 `-am`）：

```powershell
mvn -pl bootstrap -am test
```

前端构建：

```powershell
Set-Location -LiteralPath .\web
npm run build
```

V3 Docker 验收结果：Maven reactor `140/140` 通过，0 failure、0 error、0 skipped；8 个 Compose 服务健康，Vite 生产构建通过。完整证据见 [docs/v3-docker-acceptance-report.md](docs/v3-docker-acceptance-report.md)。

真实模型和真实 PDF 验证需要额外的 API key、Docker 服务或环境变量：

```powershell
mvn -pl bootstrap -am test -Dtest=AiClientRealIT
mvn -pl rag-core -am test -Dtest=RealPdfVerifyIT
```

真实集成验收应使用 `docker compose --profile full up -d` 启动 PostgreSQL、Redis、MinIO、Docling、RocketMQ、Neo4j 和 Jaeger。上下文测试依赖外部中间件；未启动 Docker 时应区分环境失败和业务单测失败。

## CI

GitHub Actions（`.github/workflows/ci.yml`）针对 `main` 分支的 push / pull request：

- **backend**：`setup-java`（Temurin 21）→ `mvn -B -pl infra-ai,rag-core -am test`（确定性单测）→ `mvn -B -pl bootstrap -am -DskipTests package`（reactor 编译打包）。
- **frontend**：`setup-node`（Node 20）→ `npm ci` → `npm run build`。

真实模型集成测试（`AiClientRealIT`）、真实 PDF 验证（`RealPdfVerifyIT`）和 Docker 全链路验收需要本地依赖或环境变量，未纳入当前 CI；V3 已在本地完成 Docker 全链路验收。

## 项目结构

```text
RAG2Agent/
├── bootstrap/          # Spring Boot 启动：Controller、Agent、评测、MQ 消费、数据访问
├── framework/          # 通用响应、错误、Redis、鉴权、持久化基础设施
├── infra-ai/           # AI provider 抽象：Chat/Embedding/Rerank/VectorStore
├── rag-core/           # 解析、切块、检索、融合、重排、Prompt 核心
├── mcp-server/         # MCP Java SDK 2.0 Streamable HTTP 服务
├── web/                # Vue 3 前端
├── docker/             # 中间件镜像与 PostgreSQL 初始化脚本（001-015）
├── scripts/            # 健康检查、评测数据准备/运行脚本
├── docs/               # 技术选型、开发计划、翻车记录、TODO、评测清单等
└── .github/workflows/  # CI
```

## 文档

- [docs/tech-selection.md](docs/tech-selection.md)：技术选型、模块边界、配置和演进路线
- [docs/development-plan.md](docs/development-plan.md)：阶段计划与实际落地状态
- [docs/enterprise-agent-upgrade-plan.md](docs/enterprise-agent-upgrade-plan.md)：企业级多模态 Agent 升级功能、选型、数据契约与验收计划
- [docs/pitfalls-and-verification.md](docs/pitfalls-and-verification.md)：端口、中间件和真实验收翻车记录
- [docs/observability.md](docs/observability.md)：指标命名、标签和 `outcome` 枚举约定
- [docs/todo.md](docs/todo.md)：按优先级维护的工程问题、风险和后续任务
- [docs/evaluation-checklist.md](docs/evaluation-checklist.md)：评测数据集、指标和可复现实验入口
- [docs/v2-validation-report.md](docs/v2-validation-report.md)：V2 集成验收记录与真实证据
- [docs/v3-docker-acceptance-report.md](docs/v3-docker-acceptance-report.md)：V3 多模态、GraphRAG、多 Agent、ACL 与 Gold Set 验收
- [docs/gold-set/fixtures/](docs/gold-set/fixtures/)：可复现 HTML/跨页表格夹具

## 边界与路线图

V3 已落地多模态、跨页表格、局部/全局 GraphRAG、MCP 2.0、持久 Agent Runtime、并行多 Agent、ACL 和五类 Gold Set；完整路线与待办见 [docs/todo.md](docs/todo.md)。

- 权限已覆盖 tenant/org/kb、可见性和成员角色，但数据库 RLS/强制租户视图、OIDC/SSO、MCP OAuth 授权服务器、工具沙箱、SSRF/DLP/PII 和字段级脱敏仍待补齐。
- 部署基线是单机 Docker Compose；尚未提供 app Dockerfile、K8s/Helm、多实例无状态扩容、存储主备、备份恢复演练和容量基线。
- Agent 已持久化 Session/Event/Checkpoint、任务图、记忆、审批、取消和预算；异步多 Agent outbox、分布式死锁检测、检查点时间旅行和 Skill 注册仍未实现。
- Vision provider 可配置并在失败时降级 Docling OCR；云端 Qwen3-VL 需要有效 `DASHSCOPE_API_KEY` 后完成真实语义描述验收，跨模态重排和区域高亮仍属后续能力。
- GraphRAG 已具备实体/关系/证据边、局部路径、GDS 社区和摘要；实体消解、增量图更新、图谱评测集和全局摘要自动路由仍需补强。
- 五类 Gold Set 已具备证据契约和覆盖率门禁；当前使用可复现夹具，仍需扩充真实脱敏业务文档、问答和预注册阈值。
- CI 当前覆盖确定性单测、reactor 打包和前端构建；Docker 全链路、容量压测、混沌演练、SAST/DAST 和 SBOM 尚未纳入自动门禁。
- Embedding 片段级缓存复用未变切片；模型升级后仍需对存量文档 `reingest` 重建库内向量。
- 当前 SQL 依赖 PostgreSQL 的 pgvector、JSONB、数组和全文能力，不提供 SQLite 替代方案或 GraalVM 单文件发行物。

## 参与贡献

欢迎通过 Issue / PR 参与。仓库采用 Conventional Commits，提交前缀使用 `feat:` / `fix:` / `docs:` / `chore:` / `refactor:` / `test:`；提交前确认 git 状态不含 `.env` 或任何 `sk-` 开头的密钥。涉及数据库表结构变更时，需同步更新 `docker/postgres/init/*.sql`。
