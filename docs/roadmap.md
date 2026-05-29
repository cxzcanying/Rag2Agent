# Roadmap

## Phase 1: 基础工程框架

- Maven 多模块工程。
- Spring Boot 主启动模块。
- Docker Compose 中间件。
- AI 与 RAG 核心接口。
- 最小 Vue 前端。
- 健康检查、版本信息、AI Provider 占位接口。

## Phase 2: 企业业务底座

- 用户、组织、角色和权限。
- 知识库与文档元数据。
- 审计日志与操作记录。
- MinIO 文件上传链路。

## Phase 3: RAG 文档入库

- 文档解析 Pipeline。
- 文本清洗与切块。
- Embedding 与 pgvector 写入。
- 索引任务状态和失败重试。

## Phase 4: 问答与引用溯源

- 混合检索。
- Rerank。
- Prompt 构造。
- SSE 流式回答。
- 引用来源与权限过滤。

## Phase 5: 受控 Agent

- 意图识别。
- RAG 与工具调用路由。
- MCP 工具接入。
- 运行轨迹与人工确认。

## Phase 6: 生产级增强

- 模型路由、熔断、降级。
- 限流和成本统计。
- OpenTelemetry Trace。
- RAG 评测集和反馈闭环。
