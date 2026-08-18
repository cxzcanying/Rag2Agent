# RAG2Agent

RAG2Agent 是一个面向企业级 RAG + Agent 场景的后端基础工程。当前已完成第一期演示闭环，并进入 D13 评测与可观测阶段。

## 技术路线

- 后端主线：Java 21 + Spring Boot 3
- 构建工具：Maven 多模块
- 数据基础设施：PostgreSQL + pgvector、Redis、MinIO、Neo4j、RocketMQ
- AI 抽象：自建 `infra-ai` 接口层，不把核心链路绑定到 Spring AI、LangChain4j 或 Python
- 前端骨架：Vue + Vite
- 本地环境：Docker Compose
- 完整技术选型决策：[docs/tech-selection.md](docs/tech-selection.md)

## 模块说明

- `framework`：通用响应、异常处理、Redis、鉴权、ORM 等基础设施预留。
- `infra-ai`：模型供应商、Chat、Embedding、Rerank、VectorStore 抽象。
- `rag-core`：文档解析、切块、检索、重排、Prompt 构造等 RAG 核心接口。
- `mcp-server`：MCP 工具服务预留模块。
- `bootstrap`：主启动模块，提供健康检查、版本信息、AI Provider 占位接口。
- `web`：最小 Vue 前端骨架。

## 本地启动

```powershell
Copy-Item -LiteralPath .env.example -Destination .env
docker compose up -d
mvn -pl bootstrap -am spring-boot:run
```

启动后可访问：

- 后端健康检查：http://localhost:18080/api/health
- 版本信息：http://localhost:18080/api/version
- AI Provider：http://localhost:18080/api/ai/providers
- OpenAPI UI：http://localhost:18080/swagger-ui.html

> 端口使用 18080 而非 8080，是为了避开 Windows 动态保留端口区（表现为"端口占用但查不到进程"），详见 [docs/tech-selection.md](docs/tech-selection.md)。

前端开发：

```powershell
Set-Location -LiteralPath .\web
npm install
npm run dev
```

## 当前边界

当前版本已实现：

- 用户注册登录（Sa-Token）
- 文档上传与 MinIO 存储
- 知识库管理
- RAG 检索（混合检索 + RRF + Rerank）
- Agent 对话、function calling、工具审批与 SSE
- 评测用例导入、Hit@k/MRR、可选 Faithfulness/Answer Correctness、配置矩阵
- Actuator、Prometheus 指标、OpenTelemetry tracing bridge、JSON 日志

评测接口：

- `POST /api/evaluations/cases/import` 导入 `question/expectedAnswer/goldenDocumentIds`
- `POST /api/evaluations/runs` 运行单个检索配置
- `POST /api/evaluations/matrix` 顺序运行多个配置并保存结果
- `GET /api/evaluations/runs?kbId=...` 查看历史结果

可观测接口：

- `GET /actuator/health`
- `GET /actuator/prometheus`

数据库已有数据卷需要手动执行 [003_evaluation.sql](docker/postgres/init/003_evaluation.sql)；新建 PostgreSQL 数据卷会自动执行。
