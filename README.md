# RAG2Agent

RAG2Agent 是一个面向企业级 RAG + Agent 场景的后端基础工程。当前阶段提供项目骨架、模块边界、基础配置和健康检查接口；知识库、检索、问答、Agent 工作流等业务功能按 [docs/tech-selection.md](docs/tech-selection.md) 第 7 节的分期计划逐步实现。

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

尚未实现：

- Agent 工作流
- MCP 工具调用

这些功能会在后续阶段基于当前模块边界逐步实现。
