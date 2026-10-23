# RAG2Agent

企业级多模态 RAG 与受控 Agent 后端。项目基于 Java 21、Spring Boot 3.5 和 Maven 多模块构建，前端使用 Vue 3 + Vite，覆盖文档入库、混合检索、GraphRAG、持久 Agent Runtime、长期记忆、多 Agent、评测、成本账本与三层限流。

![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.7-6DB33F?logo=spring&logoColor=white)
![Vue](https://img.shields.io/badge/Vue-3-4FC08D?logo=vuedotjs&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)
![MCP](https://img.shields.io/badge/MCP-Java%20SDK%202.0-6B57FF)

## 当前状态

- 后端模块：`framework`、`infra-ai`、`rag-core`、`mcp-server`、`bootstrap`。
- 前端：`web`，包含知识库、对话、评测和成本页面。
- 数据库迁移：`docker/postgres/init/001` 至 `022`。
- 权限基线：tenant / organization / knowledge-base ACL，核心业务表启用 PostgreSQL RLS。
- 运行环境：Windows + Docker Desktop，后端固定使用 `18080`，避免 Windows 动态保留端口。

## 主要能力

| 领域 | 当前实现 |
| --- | --- |
| 账号与权限 | Sa-Token 登录、注册开关、默认租户隔离、知识库 Owner/Admin/Viewer 角色、资源继承校验、安全审计、内部管理入口和全站/租户/用户三层限流 |
| MCP | MCP Java SDK 2.0 Streamable HTTP；token → 调用方 → scopes 由服务端保存，不信任请求头自报权限 |
| 文档入库 | PDFBox、Docling、Apache POI 分流；检查真实文件签名、Office ZIP 内容和压缩规模；解析质量不合格时进入 `NEEDS_REVIEW` |
| 多模态结构 | 保存元素、逻辑表格、行级语义文本、页码、bbox、图片资产、公式、合并单元格和跨页续表 |
| 检索 | pgvector、中文关键词、Neo4j 图路径并行召回；RRF 融合、Rerank、表格上下文扩展和权限边界过滤 |
| Agent | 工具调用、人工审批、事件持久化与回放、会话恢复、上下文压缩、取消传播、模型并发舱壁和请求幂等 |
| 长期记忆 | 租户隔离、hash 去重、实体/谓词、SUPERSEDE 替代链、有效期、混合召回、访问反馈、审核与后台自动抽取 |
| 多 Agent | 按问题类型规划 document/comparison/table/graph/image/tool Worker；工具白名单在执行层硬校验；Reviewer 最多退回一次 |
| 评测 | `RAG/TABLE/MULTIMODAL/GRAPH/AGENT` 场景证据校验；TABLE/MULTIMODAL 读取真实结构数据；AGENT 执行隔离回放；可选 Langfuse dataset 导入与结果上报 |
| 成本 | 每次模型请求独立记账；记录任务、run、用户、租户、峰谷价、缓存 token 和厂商时间；支持每日预算预占、结算和成本页面 |
| 并发 | Spring 虚拟线程；评测、检索、工具、团队编排、Worker、记忆、入库和 Docling 使用独立有界线程池 |
| 可观测性 | Micrometer、Prometheus、OpenTelemetry、LLM/检索/工具/MQ 指标以及线程池队列监控 |

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.5.7、MyBatis-Plus、Sa-Token |
| 数据 | PostgreSQL 17 + pgvector + pg_trgm / 可选 zhparser、Redis、MinIO、Neo4j + GDS |
| 消息与解析 | RocketMQ、Docling Serve、PDFBox、Apache POI |
| 模型接入 | DeepSeek Chat、SiliconFlow Embedding/Rerank、Qwen3-VL Vision，均可通过配置切换 |
| 前端 | Vue 3、Vite、Element Plus |
| 观测 | Micrometer、Prometheus、OpenTelemetry、Jaeger |

## 模块结构

```text
RAG2Agent/
├── bootstrap/      # API、Agent Runtime、鉴权、评测、成本、MQ、数据库访问
├── framework/      # 通用响应、异常、Redis、鉴权和基础配置
├── infra-ai/       # Chat / Embedding / Rerank / Vision / 文档智能客户端
├── rag-core/       # 文档模型、解析器、切块、检索融合和评测指标
├── mcp-server/     # MCP Streamable HTTP 服务与鉴权
├── web/            # Vue 3 前端
├── docker/         # Compose 依赖配置与 PostgreSQL 初始化脚本
└── scripts/        # 健康检查、安全验证、压测和评测工具
```

依赖主线：`framework` / `infra-ai` 为底座，`rag-core` 依赖 `infra-ai`，`bootstrap` 聚合业务能力，`web` 调用 `bootstrap` API。

## 快速开始

### 前置条件

- Windows + PowerShell 7
- JDK 21
- Maven 3.9+
- Docker Desktop
- Node.js 20+（仅前端）
- DeepSeek 和 SiliconFlow API Key

### 1. 配置

```powershell
Copy-Item -LiteralPath .env.example -Destination .env
```

至少填写：

```text
DEEPSEEK_API_KEY=...
SILICONFLOW_API_KEY=...
```

要启用 Vision，再填写 `DASHSCOPE_API_KEY` 并设置 `RAG2AGENT_QWEN_VL_ENABLED=true`。

### 2. 启动中间件

基础环境：

```powershell
docker compose up -d
```

完整环境：

```powershell
docker compose --profile full up -d
```

完整 profile 会增加 RocketMQ、Neo4j 和 Jaeger。Docling sidecar 可按需启动：

```powershell
docker compose --profile docling up -d
```

### 3. 启动后端

```powershell
$env:RAG2AGENT_MQ_ENABLED="true"
$env:RAG2AGENT_DOCLING_ENABLED="true"
$env:RAG2AGENT_NEO4J_ENABLED="true"
mvn -pl bootstrap spring-boot:run
```

后端默认地址：`http://localhost:18080`。

### 4. 启动前端

```powershell
Set-Location -LiteralPath .\web
npm install
npm run dev
```

Vite 会把 `/api` 代理到 `http://localhost:18080`。

## 数据库迁移

新数据卷会自动执行 `docker/postgres/init/001` 至 `022`。已有数据卷不会重复执行初始化脚本，需要按顺序手动补齐，尤其注意：

```text
016_security_hardening.sql
017_evaluation_hardening.sql
018_memory_compaction.sql
019_ingestion_quality.sql
020_cost_ledger.sql
021_agent_evidence_and_memory_jobs.sql
022_ai_budget_reservation.sql
```

迁移 `016` 会创建非超级用户 `rag2agent_app` 并为核心业务表启用强制 RLS。应用连接固定使用该角色，数据库初始化继续使用 `POSTGRES_USER`。

## 常用端口

| 服务 | 宿主端口 |
| --- | ---: |
| Spring Boot | 18080 |
| PostgreSQL | 15432 |
| Redis | 16379 |
| MinIO API / Console | 19000 / 19001 |
| Docling | 15001 |
| Neo4j Browser / Bolt | 17474 / 17687 |
| RocketMQ NameServer | 19876 |
| Jaeger UI / OTLP | 16686 / 4317 / 4318 |

## 关键配置

配置统一使用 `${VAR:default}` 占位符。`.env` 由 `DotenvEnvironmentPostProcessor` 向上查找并加载，系统环境变量优先。

```text
RAG2AGENT_REGISTRATION_MODE=DISABLED
RAG2AGENT_ADMIN_USERNAMES=
RAG2AGENT_DEFAULT_TENANT_ID=1
RAG2AGENT_DEFAULT_ORGANIZATION_ID=1
RAG2AGENT_RATE_LIMIT=60
RAG2AGENT_RATE_LIMIT_TENANT=600
RAG2AGENT_RATE_LIMIT_GLOBAL=6000
RAG2AGENT_AI_DAILY_BUDGET_USD=0
RAG2AGENT_MEMORY_EXTRACTION_ENABLED=false
LANGFUSE_ENABLED=false
LANGFUSE_DATASET_NAME=
LANGFUSE_DATASET_VERSION=
```

生产环境必须提供非默认 MCP token、管理员名单和真实数据库凭据。注册模式默认关闭，开放注册前必须确认租户和组织归属策略。

### 模型价格与预算

DeepSeek Flash 默认价格按官方口径配置：

- 缓存未命中输入：`0.15 USD / 百万 token`
- 缓存命中输入：`0.003 USD / 百万 token`
- 输出：`0.60 USD / 百万 token`
- 北京时间工作日高峰时段价格翻倍

设置 `RAG2AGENT_AI_DAILY_BUDGET_USD` 后，Chat 请求会先预占预算，完成后按实际请求结算。`0` 表示关闭每日预算。

### Langfuse

Langfuse 默认关闭。配置 `LANGFUSE_ENABLED` 和 API Key 后可上报运行结果，也可从版本化 dataset 导入评测用例：

```http
POST /api/evaluations/langfuse/import?kbId=1&datasetName=rag-regression
```

dataset item 的 `metadata` 需要包含内部测试知识库的 `goldenDocumentIds`；场景类型和证据分别使用 `caseType`、`evidence`。

## API 概览

除健康检查、版本和注册选项外，业务接口需要登录后的 Sa-Token。

```text
POST /api/auth/login
GET  /api/auth/me
GET  /api/auth/registration-options

GET  /api/knowledge-bases
POST /api/knowledge-bases
PATCH /api/knowledge-bases/{kbId}/visibility
GET  /api/knowledge-bases/{kbId}/members
POST /api/knowledge-bases/{kbId}/members
DELETE /api/knowledge-bases/{kbId}/members/{userId}

POST /api/documents/upload
GET  /api/documents?kbId={kbId}
GET  /api/documents/{documentId}/elements
GET  /api/documents/{documentId}/tables/{tableId}/rows
GET  /api/documents/{documentId}/assets/{assetId}
POST /api/documents/{documentId}/assets/{assetId}/describe

GET  /api/search
POST /api/search/multimodal
POST /api/chat
POST /api/agent/approvals/{runId}
POST /api/agent/runs/{runId}/cancel
GET  /api/agent/runs/{runId}
GET  /api/agent/runs/{runId}/tasks
GET  /api/agent/sessions/{sessionId}/events
GET  /api/agent/memories
GET  /api/agent/memories/search
GET  /api/agent/memories/review
POST /api/agent/memories/review/{memoryId}
DELETE /api/agent/memories/{memoryId}

POST /api/agent/teams/research
POST /api/agent/teams/research/async
POST /api/agent/teams/compare

POST /api/evaluations/cases/import
POST /api/evaluations/langfuse/import
POST /api/evaluations/runs
POST /api/evaluations/matrix
GET  /api/evaluations/runs
GET  /api/evaluations/runs/{runId}
GET  /api/evaluations/runs/{runId}/results
POST /api/evaluations/runs/{runId}/cancel
GET  /api/evaluations/gold-set/coverage

GET  /api/costs/summary
GET  /api/costs/budget
POST /api/costs/balances

GET  /api/health
GET  /api/version
GET  /actuator/health
GET  /actuator/prometheus
```

Swagger UI：`http://localhost:18080/swagger-ui.html`

## 评测

评测提交立即返回 `runId`，后台按 `QUEUED → RUNNING → COMPLETED/COMPLETED_WITH_ERRORS/FAILED/CANCELLED/TIMEOUT` 执行。`eval_run` 和 `eval_case_result` 是权威事实源。

五类场景：

- `RAG`：文本证据和文档排名。
- `TABLE`：读取真实表格、完整表头、目标行和续表。
- `MULTIMODAL`：校验文档资产 ID、描述和引用。
- `GRAPH`：校验实体、图节点和路径元数据。
- `AGENT`：执行隔离 Agent run，校验工具集合、步数、终态和回答。

基础设施异常单独归类，不进入 Hit@K / MRR 聚合。生成评测启用 `evaluateGeneration=true` 后会额外调用回答模型和裁判模型。

## 测试与验证

后端：

```powershell
mvn -pl bootstrap -am test
```

前端：

```powershell
Set-Location -LiteralPath .\web
npm run build
```

安全与运行检查：

```powershell
pwsh ./scripts/verify-security-hardening.ps1 `
  -CredentialsPath ./eval-data/miracl/prepared/credentials.json `
  -ExpectRegistrationDisabled `
  -ExpectRuntimeConfigForbidden `
  -VerifyRedisFallback
```

容量报告：

```powershell
pwsh ./scripts/run-capacity-report.ps1
```

真实模型和真实 PDF 验证会产生 API 费用或依赖本地文件，不属于默认 CI：

```powershell
mvn -pl bootstrap -am test -Dtest=AiClientRealIT
$env:VERIFY_PDF_PATHS="C:\path\a.pdf;C:\path\b.pdf"
mvn -pl rag-core -am test -Dtest=RealPdfVerifyIT
```

## 安全基线

- 禁止提交 `.env`、真实 API Key、生产数据库口令和默认 MCP token。
- 高风险工具需要审批，SSE 断开会取消当前 Agent run。
- 业务 SQL 必须保留 `kb_id` 权限条件和 `c.version = d.version` 当前版本条件。
- Redis 或鉴权依赖故障时，上传、搜索、AI 和 Agent 高风险接口返回明确错误，不继续执行。
- 管理端点、指标、运行配置和厂商余额按管理员/内网策略限制。
- 数据库表结构变更必须同步增加新的 `docker/postgres/init/*.sql`，并记录旧数据卷升级步骤。

## 已知边界

- 部署基线是单机 Docker Compose，尚未提供生产级 K8s、Helm、多实例无状态扩容和正式备份演练。
- 厂商余额目前由管理员登记，不自动调用厂商接口对账。
- 容量报告脚本已提供，但具体并发能力依赖目标机器执行结果。
- Langfuse 用于 dataset 导入和结果上报，不替代项目内场景证据判断。
- 自动记忆抽取默认关闭，需要独立配置和模型成本预算。

## 相关文档

- [技术选型](docs/tech-selection.md)
- [开发计划](docs/development-plan.md)
- [踩坑与验证](docs/pitfalls-and-verification.md)
- [评测清单](docs/evaluation-checklist.md)
- [工程 TODO](docs/todo.md)

## 提交规范

使用 Conventional Commits：

```text
feat: 新功能
fix: 缺陷修复
docs: 文档
chore: 工程配置
refactor: 重构
test: 测试
```

提交前确认 `git status` 不包含 `.env` 或任何真实密钥。
