# AGENTS.md

This file provides guidance to your coding CLI (developers.openai.com/codex) when working with code in this repository.

## 项目简介

RAG2Agent：企业级 RAG + Agent 后端工程。Java 21 + Spring Boot 3.5，Maven 多模块（framework / infra-ai / rag-core / mcp-server / bootstrap），前端 Vue 3 + Vite（web/）。完整选型与演进见 docs/tech-selection.md，阶段计划见 docs/development-plan.md。

## 常用命令

- 后端编译/测试/打包：`mvn -pl bootstrap -am <compile|test|package>`（必须带 `-am`，否则兄弟模块 jar 找不到）
- 中间件：`docker compose up -d`（PostgreSQL+pgvector+pg_trgm、Redis、MinIO、RocketMQ、Neo4j）
- 前端：`cd web; npm install; npm run dev`（vite 代理 `/api` → `localhost:18080`）
- 真实模型集成测试（会消耗 API 额度，手动跑）：`mvn -pl bootstrap -am test -Dtest=AiClientRealIT`
- 真实 PDF 验证（需设 `VERIFY_PDF_PATHS` 环境变量）：`mvn -pl rag-core -am test -Dtest=RealPdfVerifyIT`

## 端口（Windows 环境硬约束）

后端 18080、PostgreSQL 15432、Neo4j 17474/17687。标准端口（8080、5432、7474 等）会被 Windows 动态保留端口区占用，症状是"端口占用但查不到进程"——不要改回标准端口，详见 docs/pitfalls-and-verification.md。

## 密钥与配置

- 项目根 `.env` 含 DeepSeek / SiliconFlow API Key，已被 .gitignore 忽略；**禁止把真实密钥写进任何提交**。
- 配置一律用 `${VAR:default}` 占位符，不硬编码。`.env` 由 DotenvEnvironmentPostProcessor 加载（向上查找，系统环境变量优先）。
- 模型名配置化：deepseek-v4-flash / deepseek-v4-pro、BAAI/bge-m3、BAAI/bge-reranker-v2-m3，代码里不写死模型名。

## 代码约定

- 中文注释，重点写"为什么"而不是"是什么"；新文件统一 UTF-8。
- 检索 SQL 必须带 `c.version = d.version`（只查当前版本）和 kb_id 过滤（权限边界），不要漏。
- minio 8.5.17 配套 okhttp 4.12.0，不要升 5.x（本地仓库曾出现 5.3.2 损坏/不可用）。
- 测试运行时 `user.dir` 是模块目录：`.env` 加载器向上查找；写日志/报告用绝对路径或接受模块目录落点。
- 数据库表结构变更同步更新 docker/postgres/init/*.sql，并注意 init 脚本只在卷首次初始化时执行。

## 提交

- Conventional Commits：`feat:` / `fix:` / `docs:` / `chore:` / `refactor:` / `test:`。
- 提交前确认 git 状态不含 `.env` 或任何 `sk-` 开头的密钥。

## 文档索引

- docs/architecture.md：系统架构总览
- docs/tech-selection.md：技术选型决策
- docs/development-plan.md：阶段计划与验收
- docs/pitfalls-and-verification.md：翻车记录（必读）
- docs/todo.md：工程问题 TODO
- docs/evaluation-checklist.md：D13 评测清单
- docs/configuration-secrets.md：配置与密钥管理
- docs/roadmap.md：路线图
