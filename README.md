# RAG2Agent

> 企业级 RAG + 可控 Agent 后端工程。基于 Java 21 + Spring Boot 3.5 的多模块后端与 Vue 3 前端，本地跑通「登录 → 上传 PDF → 异步入库 → 混合检索 → 带引用回答 → Agent 工具审批 → 异步评测」完整闭环，重点验证可靠性、可观测性和任务恢复边界。

![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.7-6DB33F?logo=spring&logoColor=white)
![Vue](https://img.shields.io/badge/Vue-3-4FC08D?logo=vuedotjs&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)

这是一个依赖本地中间件的工程样例，不是开箱即用的单文件产品。当前权限边界是知识库 owner 级别，Neo4j 仍属基础设施预留；MCP 已提供项目内 JSON-RPC HTTP 子集，GraphRAG 尚未实现。

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
| 账号与权限 | 注册、登录、Sa-Token 会话；知识库 owner 校验覆盖文档、检索、Agent 工具、评测和审批入口 |
| 文档入库 | PDF 上传到 MinIO；RocketMQ 异步处理；PDF 解析、递归切块、Embedding、当前版本 chunk 写入 PostgreSQL/pgvector |
| 检索 | Query Routing；向量检索与 `pg_trgm` 关键词检索并行；RRF 融合；可选 Rerank；返回引用来源 |
| Agent | OpenAI-compatible function calling；工具 schema/权限/审计/超时；写操作审批；SSE 对话；上下文压缩和最大步数后的无工具总结 |
| AI 可靠性 | 超时、重试、熔断、降级、并发舱壁；Redis Lua 用户限流；失败响应统一脱敏 |
| 缓存 | Caffeine L1 + Redis L2 Embedding 查询缓存和 single-flight；内容寻址 key 可复用未改变文本的结果 |
| 可观测性 | Micrometer 指标、Prometheus、OpenTelemetry OTLP、Jaeger、JSON 日志；HTTP/检索/LLM/工具/MQ trace context 基础链路 |
| 评测 | 用例导入、单配置/矩阵提交；异步 run、进度查询、逐题结果、取消、失败/超时、重启恢复和 `Idempotency-Key` 幂等 |
| 前端 | Vue 3 + Vite；登录、知识库/文档、对话审批、评测进度和取消页面 |

## 项目亮点

RAG2Agent 的定位不是「能检索、能对话」的单点 Demo，而是把企业内部落地最常踩的可靠性、可观测性、任务恢复和成本治理做成内建回路的企业级 RAG + Agent 底座样例。相比直接用通用框架拼装或常见快速 Demo，差异集中在下面几点，且都有可复现的验收证据：

| 维度 | 本项目（RAG2Agent） | 通用框架 / 快速 Demo 的常见取舍 |
| --- | --- | --- |
| 可靠性 | 请求级幂等（`clientRequestId` 唯一约束）+ 会话锁租约续期、Redis Lua 限流、超时/重试/熔断/降级、并发舱壁、失败响应统一脱敏 | 通常依赖中间件或插件编排，幂等、限流、熔断与恢复边界往往要自己补齐 |
| 可观测性 | Micrometer + Prometheus + OpenTelemetry/Jaeger，HTTP → 检索 → LLM → 工具 → MQ 全链路 trace context，JSON 结构化日志，业务指标命名与 `outcome` 约定 | 常见只有调用日志或 HTTP 监控，链路追踪、业务指标与日志串联多是后续拼装 |
| 任务恢复 | 异步入库与评测断点续跑、应用重启从 `eval_run` 恢复、RocketMQ DLQ 死信读取回投、评测任务幂等 | 任务状态常放内存或依赖线程，重启/超时/重复消费的恢复边界不清晰 |
| 检索与评测 | 向量 + `pg_trgm` 关键词双路 + RRF 融合 + 可选重排，内置用例导入/单配置/矩阵评测（Hit@k、MRR、Faithfulness）、可复现实验入口 | 检索通常只给链路，评测数据、指标与调参闭环要单独搭建 |
| 中文检索 | `zhparser` 中文分词 + 二元切分回退，用固定 MIRACL zh 子集做 A/B，关键词 Hit@5 有可量化提升 | 通用检索对中文关键词召回普遍偏弱，且常无本地评测佐证 |
| 成本治理 | AI usage 账本（`ai_usage_ledger`），价格版本/币种/分时与缓存 token 单价，Token 估算偏差校准 | 常见缺少可对账的成本数据，额度与开销难以回溯 |
| Agent 安全 | 工具 schema/权限/审计/超时、写操作人工审批、无引用不答、提示注入规则信号、最大步数降级 | 工具调用常见「能用」即止，审批、审计、注入防护边界要自行设计 |
| 排查闭环 | 前端聊天消息携带 traceId（诊断 ID），错误日志可按 traceId 检索 | 前端与排查链路常割裂，出问题时难以定位到具体请求 |

这些能力可以在本地 `docker compose --profile full up -d` + 真实模型上复现，验收证据见 [docs/v2-validation-report.md](docs/v2-validation-report.md)。它仍是工程样例而非生产成品：多租户 ACL、GraphRAG、配置中心、财务审计、自动调参数据飞轮等仍属路线图（见[边界与路线图](#边界与路线图)）。

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
| 图数据库 | Neo4j | 5（Community，当前仅预留） |
| 链路追踪 | Jaeger | 1.60（all-in-one） |
| 覆盖率 / 校验 | Spotless | 2.46.1（代码格式校验） |

`infra-ai` 提供 Chat、Embedding、Rerank 抽象，当前配置了 DeepSeek（chat）和 SiliconFlow（embedding / rerank / 定价）。

## 架构与模块

多模块 Maven 工程，各模块职责如下：

| 模块 | 职责 |
| --- | --- |
| `framework` | 通用响应、错误处理、Redis、鉴权和持久化基础设施 |
| `infra-ai` | 按 capability 选择 active provider；Chat、Embedding、Rerank、VectorStore 接口和客户端 |
| `rag-core` | 解析、切块、检索、融合、重排和 Prompt 相关核心能力 |
| `mcp-server` | MCP 远程工具发现/调用契约与 HTTP JSON-RPC 服务 |
| `bootstrap` | Spring Boot 启动模块、Controller、Agent、评测、MQ 消费和数据库访问 |
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

# Demo：PostgreSQL + Redis + MinIO（默认）
$env:RAG2AGENT_MQ_ENABLED="false"
docker compose up -d

# 完整拓扑：追加 RocketMQ、Neo4j、Jaeger
docker compose --profile full up -d

# 完整异步入库需开启 RocketMQ（PowerShell）
$env:RAG2AGENT_MQ_ENABLED="true"
mvn -pl bootstrap spring-boot:run
```

`application.yml` 默认使用 `dev` profile，连接宿主机映射端口。首次启动会由 PostgreSQL 初始化脚本自动建表；复用旧数据卷时需手动按顺序补跑未执行的初始化脚本（包括 [006_ai_usage_ledger.sql](docker/postgres/init/006_ai_usage_ledger.sql)）。初始化脚本只会在新卷首次启动时自动执行。

中文关键词检索默认使用 `RAG2AGENT_CHINESE_SEARCH_MODE=auto`：检测到 `zhparser` 时走 PostgreSQL `tsvector`，否则回退二元切分。要启用 PG17 的 `zhparser` 镜像：

```powershell
docker build -t rag2agent-postgres:pg17-zhparser docker/postgres
$env:RAG2AGENT_POSTGRES_IMAGE="rag2agent-postgres:pg17-zhparser"
docker compose up -d
```

构建失败时继续使用默认 `pgvector/pg17` 即可。

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
| RocketMQ | 19876、19091、19111、19112 | NameServer、Broker 通信 |
| Neo4j | 17474 / 17687 | Browser / Bolt；当前仅预留 |
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
```

`RAG2AGENT_MQ_ENABLED`、`RAG2AGENT_CHINESE_SEARCH_MODE`、`RAG2AGENT_EMBEDDING_*`、`RAG2AGENT_RATE_LIMIT_*`、`RAG2AGENT_AI_RESILIENCE_*`、`RAG2AGENT_MCP_REMOTE_*` 等均可在 `.env` 或环境变量中覆盖，默认值见 `bootstrap/src/main/resources/application.yml`。价格相关变量以 `DEEPSEEK_*_PRICE_*` / `SILICONFLOW_*_PRICE_*` 前缀为主，默认使用官方分时 / 免费用量口径。

Embedding 缓存 key 使用 provider、model、维度和文本 hash 做内容寻址，不包含文档版本号，因此同一文本可跨文档版本复用；模型版本变更时应调整 model 配置或清理对应缓存空间，不能把旧模型结果当作新模型结果。

## API 概览

除健康检查、版本和 provider 查询外，业务接口需要登录后的 Sa-Token；

```text
POST /api/auth/register
POST /api/auth/login
GET  /api/auth/me

POST /api/knowledge-bases
GET  /api/knowledge-bases
POST /api/documents/upload
GET  /api/documents?kbId={kbId}
GET  /api/documents/{id}/presign
POST /api/documents/{id}/reingest
GET  /api/search?kbId={kbId}&query={query}&topK={topK}

POST /api/chat                         # SSE
POST /api/agent/approvals/{runId}

POST /api/evaluations/cases/import
POST /api/evaluations/runs
POST /api/evaluations/matrix
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
      "goldenDocumentIds": [12]
    }
  ]
}
```

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

真实模型和真实 PDF 验证需要额外的 API key、Docker 服务或环境变量：

```powershell
mvn -pl bootstrap -am test -Dtest=AiClientRealIT
mvn -pl rag-core -am test -Dtest=RealPdfVerifyIT
```

在未启动 MinIO 的机器上，部分 Spring context 测试可能失败；这属于外部依赖未就绪，不等同于业务单测失败。全仓 `spotless:check` 还可能被既有 `.eval-tools`、`node_modules` 等非源码目录影响，验证时应以 Maven 测试和前端构建为主。

## CI

GitHub Actions（`.github/workflows/ci.yml`）针对 `main` 分支的 push / pull request：

- **backend**：`setup-java`（Temurin 21）→ `mvn -B -pl infra-ai,rag-core -am test`（确定性单测）→ `mvn -B -pl bootstrap -am -DskipTests package`（reactor 编译打包）。
- **frontend**：`setup-node`（Node 20）→ `npm ci` → `npm run build`。

真实模型集成测试（`AiClientRealIT`）和真实 PDF 验证（`RealPdfVerifyIT`）需要本地依赖或环境变量，未纳入 CI。

## 项目结构

```text
RAG2Agent/
├── bootstrap/          # Spring Boot 启动：Controller、Agent、评测、MQ 消费、数据访问
├── framework/          # 通用响应、错误、Redis、鉴权、持久化基础设施
├── infra-ai/           # AI provider 抽象：Chat/Embedding/Rerank/VectorStore
├── rag-core/           # 解析、切块、检索、融合、重排、Prompt 核心
├── mcp-server/         # MCP 远程工具发现/调用契约与 HTTP JSON-RPC 服务
├── web/                # Vue 3 前端
├── docker/             # 中间件镜像与 PostgreSQL 初始化脚本（001-007）
├── scripts/            # 健康检查、评测数据准备/运行脚本
├── docs/               # 技术选型、开发计划、翻车记录、TODO、评测清单等
└── .github/workflows/  # CI
```

## 文档

- [docs/tech-selection.md](docs/tech-selection.md)：技术选型、模块边界、配置和演进路线
- [docs/development-plan.md](docs/development-plan.md)：阶段计划与实际落地状态
- [docs/pitfalls-and-verification.md](docs/pitfalls-and-verification.md)：端口、中间件和真实验收翻车记录
- [docs/observability.md](docs/observability.md)：指标命名、标签和 `outcome` 枚举约定
- [docs/todo.md](docs/todo.md)：按优先级维护的工程问题、风险和后续任务
- [docs/evaluation-checklist.md](docs/evaluation-checklist.md)：评测数据集、指标和可复现实验入口
- [docs/v2-validation-report.md](docs/v2-validation-report.md)：V2 集成验收记录与真实证据

## 边界与路线图

以下内容已记录在 [docs/todo.md](docs/todo.md)，README 不把它们当作已交付能力：

- 已接入 provider usage 与估算 token 校准指标、AI usage 账本（含缓存 token/价格版本字段）和中文检索：DeepSeek 默认使用官方 USD 分时价格，SiliconFlow BGE 模型按官方免费价记录，仍可由环境变量覆盖；默认无扩展时使用二元切分，启用 `rag2agent-postgres:pg17-zhparser` 后使用 `zhparser`；固定 MIRACL zh 子集验证了关键词和混合检索收益。完整验收证据见 [V2 集成验收记录](docs/v2-validation-report.md)。
- SSE 已支持 Redis Stream + `Last-Event-ID` 增量回放；超出保留窗口时回退到 run 状态查询，多行事件/半包兼容性和生产级长连接治理仍需继续补强。
- Agent 已有同一 session 的并发锁、最大步数降级和完整工具审计；`clientRequestId` 请求级幂等与长任务锁租约续期已落地。
- 现有 ACL 是 owner-only，不是带共享、协作组、只读/可写/管理员角色的多租户模型；输入防御、资源配额和队列积压治理也仍在 TODO。
- Neo4j 仅随 Compose 启动并保留端口，尚无实体抽取、Cypher 查询或 GraphRAG 检索链路；当前检索基线是向量 + 关键词。
- 没有 SQLite 替代方案或 GraalVM 单文件发行物。当前 SQL 依赖 PostgreSQL 的 pgvector、JSONB、数组和全文能力，完整 Compose 是本地演示前提。
- 评测结果目前用于人工比较切块/路由/重排配置，尚无自动调参、线上点赞/点踩反馈回流、Bad Case 自动入集的数据飞轮。
- Agent 当前是带工具调用的状态机，尚未实现完整 Plan-and-Execute、Self-Reflection 或长期/情景记忆。

## 参与贡献

欢迎通过 Issue / PR 参与。仓库采用 Conventional Commits，提交前缀使用 `feat:` / `fix:` / `docs:` / `chore:` / `refactor:` / `test:`；提交前确认 git 状态不含 `.env` 或任何 `sk-` 开头的密钥。涉及数据库表结构变更时，需同步更新 `docker/postgres/init/*.sql`。
