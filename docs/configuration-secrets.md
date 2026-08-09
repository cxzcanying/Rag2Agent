# 配置与密钥管理

> 核心原则：**配置与密钥分离**。配置（地址、模型名、开关）可以进仓库；密钥（API Key、密码）永远不进仓库。

## 1. 为什么用 .env

- 本地开发需要真实的 API Key 和数据库口令，但项目是公开仓库，密钥提交即泄露。
- `.env` 位于项目根目录，被 `.gitignore` 忽略，只存在于本地，不进入任何提交、镜像或制品。
- 应用启动时由自写的 `DotenvEnvironmentPostProcessor` 加载（自动向上查找 `.env`）：
  - **系统环境变量优先**（生产/容器直接注入），`.env` 只补缺（本地开发）；
  - 不引入 spring-dotenv 的原因：该库停留在 2023 年的 4.0.0，与 Spring Boot 3.5 不兼容，实测未加载 `.env`，自写实现 20 行可控。
- 代码中所有敏感值通过 `${VAR:默认值}` 占位符注入，零硬编码。
- 对外接口不返回密钥：`/api/ai/providers` 仅暴露名称、地址、能力，不含 apiKey。

## 2. 当前密钥清单（仅本地 .env）

- DeepSeek：`DEEPSEEK_API_KEY`、`DEEPSEEK_MODEL`、`DEEPSEEK_MODEL_PRO`
- 硅基流动：`SILICONFLOW_API_KEY`、`SILICONFLOW_EMBEDDING_MODEL`、`SILICONFLOW_RERANK_MODEL`
- 本地中间件默认口令：PostgreSQL / MinIO / Neo4j（仅开发环境默认值）

## 3. 未来企业级密钥管理（演进路径）

### 阶段 1（当前）：.env + 环境变量
本地开发兜底方案，密钥只存在于开发机，不进入仓库与制品。

### 阶段 2：容器 / 部署环境注入

- Docker Compose：`environment` 引用宿主环境变量，或使用 Docker Secrets（Swarm 模式）；
- Kubernetes：`Secret` 对象挂载为环境变量或文件，Deployment 引用，镜像内不烧录密钥；
- 原则：密钥在启动时注入，运行环境不落盘明文。

### 阶段 3：专用密钥管理服务（生产标准）

- 云厂商托管：AWS Secrets Manager、阿里云 KMS、腾讯云凭据管理系统；
- 自建开源：HashiCorp Vault（动态密钥、租约、轮换、审计）；
- 接入方式：应用启动时通过 SDK 拉取密钥并注入 Spring Environment（如 Spring Cloud Config + Vault 集成），运行时本地不持有明文密钥；
- 配套机制：密钥定期轮换、最小权限（每服务独立密钥）、访问审计、异常告警。

## 4. 落地检查清单

- [ ] git 中不存在任何真实密钥（含 git log 历史）
- [ ] 配置文件只有 `${VAR:默认值}` 占位符，无硬编码
- [ ] 对外接口不返回密钥
- [ ] 生产环境密钥从密钥管理服务注入，本地不分发明文
