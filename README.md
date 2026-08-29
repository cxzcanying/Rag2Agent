# RAG2Agent

RAG2Agent 是一个面向企业知识库问答和 Agent 工作流的 Java + Vue 演示工程。当前版本已经跑通“用户登录 → 上传 PDF → 异步入库 → 混合检索 → 带引用回答 → Agent 工具审批 → 异步评测”的本地闭环，重点验证可靠性、可观测性和任务恢复边界。

这仍是一个需要本地中间件的工程样例，不是开箱即用的单文件产品。当前 ACL 是知识库 owner 级别，Neo4j 和 MCP 只完成基础设施/模块预留，GraphRAG 和远程 MCP transport 尚未实现。

## 当前能力

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

## 技术栈与模块

- Java 21、Spring Boot 3.5、Maven 多模块
- Vue 3、Vite、Element Plus（`web/`）
- PostgreSQL 17 + pgvector/pg_trgm、Redis 7.4、MinIO、RocketMQ 5.3.2、Neo4j 5、Jaeger 1.60
- `infra-ai` 提供 Chat、Embedding、Rerank 抽象，当前配置了 DeepSeek 和 SiliconFlow

模块职责：

- `framework`：通用响应、错误处理、Redis、鉴权和持久化基础设施
- `infra-ai`：按 capability 选择 active provider；Chat、Embedding、Rerank、VectorStore 接口和客户端
- `rag-core`：解析、切块、检索、融合、重排和 Prompt 相关核心能力
- `mcp-server`：MCP 远程工具发现/调用契约；bootstrap 已有适配和本地 fallback，当前没有完整网络传输实现
- `bootstrap`：Spring Boot 启动模块、Controller、Agent、评测、MQ 消费和数据库访问
- `web`：Vue 前端

## 快速启动

### 前置条件

- Windows + Docker Desktop
- JDK 21 和 Maven 3.9+
- Node.js 18+（仅运行前端时需要）
- DeepSeek Chat key；SiliconFlow key 用于 BGE-M3 Embedding 和 Rerank

### 后端与中间件

在项目根目录执行：

~~~powershell
Copy-Item -LiteralPath .env.example -Destination .env
# 编辑 .env，至少填写 DEEPSEEK_API_KEY 和 SILICONFLOW_API_KEY
# Demo：PostgreSQL + Redis + MinIO（默认）
$env:RAG2AGENT_MQ_ENABLED="false"
docker compose up -d
# 完整 V2：追加 RocketMQ、Neo4j、Jaeger
docker compose --profile full up -d
# 完整异步入库需开启 RocketMQ（PowerShell）
$env:RAG2AGENT_MQ_ENABLED="true"
mvn -pl bootstrap spring-boot:run
~~~

`application.yml` 默认使用 `dev` profile，连接宿主机映射端口。首次启动会由 PostgreSQL 初始化脚本创建表；已有 PostgreSQL 数据卷需要手动执行 [003_evaluation.sql](docker/postgres/init/003_evaluation.sql) 才能启用评测表。

V2 已落地能力与面试用未来演进路线见 [docs/interview-evolution.md](docs/interview-evolution.md)。

### 前端

另开终端执行：

~~~powershell
Set-Location -LiteralPath .\web
npm install
npm run dev
~~~

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

项目刻意使用高位端口，避免 Windows 动态保留端口导致“端口占用但查不到进程”。端口和中间件注意事项见 [docs/pitfalls-and-verification.md](docs/pitfalls-and-verification.md)。

## 配置与安全

`.env` 已被 `.gitignore` 忽略，禁止把真实密钥提交到 Git。配置由 `DotenvEnvironmentPostProcessor` 从项目根目录向上查找 `.env`，系统环境变量优先。

常用变量：

~~~text
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
~~~

Embedding 缓存 key 使用 provider、model、维度和文本 hash 做内容寻址，不包含文档版本号，因此同一文本可跨文档版本复用；模型版本变更时应调整 model 配置或清理对应缓存空间，不能把旧模型结果当作新模型结果。

## API 概览

除健康检查、版本和 provider 查询外，业务接口需要登录后的 Sa-Token：

~~~text
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
~~~

Swagger UI：`http://localhost:18080/swagger-ui.html`；OpenAPI JSON：`http://localhost:18080/v3/api-docs`。

## 异步评测

评测提交接口不会等待全部用例完成，而是立即返回 `runId`：

~~~http
POST /api/evaluations/runs
Idempotency-Key: 8d8b4e1e-9f6f-4fc3-a8ba-2e5b0a4fbe31
Content-Type: application/json
~~~

~~~json
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
~~~

后台任务按 `QUEUED → RUNNING → COMPLETED/FAILED/CANCELLED/TIMEOUT` 更新数据库。客户端轮询 `GET /runs/{runId}` 获取状态、完成数、指标和错误信息，再按需读取 `/results` 的逐题结果。客户端断开不会停止后台任务；应用重启后会从 `eval_run` 中恢复排队/运行中的任务，已完成的 `eval_case_result` 不会重复执行。

`eval_run` 和 `eval_case_result` 是评测的最终事实源，不依赖 `run-state.json`。同一知识库下相同 `Idempotency-Key` 的并发提交只创建一个 run，重复请求返回同一 run；同一 key 搭配不同参数会稳定返回 400。矩阵提交会为子任务派生稳定幂等键。

导入用例格式：

~~~json
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
~~~

`evaluateGeneration=true` 会额外调用回答模型和裁判模型，耗时及额度明显增加，建议使用异步查询而不是等待 HTTP 请求超时。

## 测试与验证

后端业务测试（必须带 `-am`）：

~~~powershell
mvn -pl bootstrap -am test
~~~

前端构建：

~~~powershell
Set-Location -LiteralPath .\web
npm run build
~~~

真实模型和真实 PDF 验证需要额外的 API key、Docker 服务或环境变量：

~~~powershell
mvn -pl bootstrap -am test -Dtest=AiClientRealIT
mvn -pl rag-core -am test -Dtest=RealPdfVerifyIT
~~~

在未启动 MinIO 的机器上，部分 Spring context 测试可能失败；这属于外部依赖未就绪，不等同于业务单测失败。全仓 `spotless:check` 还可能被既有 `.eval-tools`、`node_modules` 等非源码目录影响，验证时应以 Maven 测试和前端构建为主。

## 当前限制与后续方向

以下内容已记录在 [docs/todo.md](docs/todo.md)，README 不把它们当作已交付能力：

- 真实 tokenizer、provider usage 对账、流式 usage 解析和按用户/知识库 Token 配额尚未完成；当前上下文预算仍包含保守估算。
- SSE 多行事件、半包和断线重连协议尚未完成；当前 SSE 适合本地演示，不应假设断线后自动续传。
- Agent 已有同一 session 的并发锁、最大步数降级和完整工具审计，但 `clientRequestId` 级别的完成后幂等与长任务锁租约续期仍待补齐。
- 现有 ACL 是 owner-only，不是带共享、协作组、只读/可写/管理员角色的多租户模型；输入防御、资源配额和队列积压治理也仍在 TODO。
- Neo4j 仅随 Compose 启动并保留端口，尚无实体抽取、Cypher 查询或 GraphRAG 检索链路；当前检索基线是向量 + 关键词。
- 没有 SQLite 替代方案或 GraalVM 单文件发行物。当前 SQL 依赖 PostgreSQL 的 pgvector、JSONB、数组和全文能力，完整 Compose 是本地演示前提。
- 评测结果目前用于人工比较切块/路由/重排配置，尚无自动调参、线上点赞/点踩反馈回流、Bad Case 自动入集的数据飞轮。
- Agent 当前是带工具调用的状态机，尚未实现完整 Plan-and-Execute、Self-Reflection 或长期/情景记忆。

## 文档索引

- [docs/tech-selection.md](docs/tech-selection.md)：技术选型、模块边界、配置和演进路线
- [docs/development-plan.md](docs/development-plan.md)：阶段计划与实际落地状态
- [docs/pitfalls-and-verification.md](docs/pitfalls-and-verification.md)：端口、中间件和真实验收翻车记录
- [docs/todo.md](docs/todo.md)：按优先级维护的工程问题、风险和后续任务
- [docs/evaluation-checklist.md](docs/evaluation-checklist.md)：评测数据集、指标和可复现实验入口

## License

项目当前未声明独立开源许可证；如需对外发布，请先补充许可证和第三方依赖清单。
