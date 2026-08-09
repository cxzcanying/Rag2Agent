# 开发笔记：雷点与验证流程

> 记录项目开发中实际踩到的坑、原因与解决方案，以及关键阶段的验证流程、命令与指标。持续更新。

## 1. .env 加载：密钥配置的雷点

### 问题现象

- Spring Boot 默认不读取 `.env` 文件，`application.yml` 里的 `${DEEPSEEK_API_KEY:}` 占位符解析为空 → 调用 DeepSeek 返回 401。
- 引入第三方库 `spring-dotenv` 4.0.0（2023 年发布，已过时）后依然无效：与 Spring Boot 3.5 不兼容，`.env` 根本没有被加载。

### 排查干扰（重点教训）

- 系统的用户环境变量里恰好存在 `SILICONFLOW_API_KEY`，导致 embedding/rerank 碰巧成功，只有 DeepSeek 401。
- 一度误以为 DeepSeek key 写错，实际是 `.env` 没生效 + 硅基流动 key 走了系统环境变量。
- 结论：排查密钥问题时先查系统环境变量（User/Machine），不要被"部分成功"误导。

### 解决方案

- 自写 `DotenvEnvironmentPostProcessor`（约 20 行，见 bootstrap/config）：
  - 实现 Spring 的 `EnvironmentPostProcessor`，在读取 application.yml **之前**执行，把 `.env` 的 key=value 注入环境；
  - **系统环境变量优先，`.env` 只补缺**（用 `containsProperty` 判断）；
  - **从当前目录向上逐级查找 `.env`**——因为测试时 `user.dir` 是模块目录（`bootstrap/`），直接找项目根会失败；
  - 通过 `META-INF/spring.factories` 注册（Boot 3 仍支持该类别的 spring.factories 注册）。
- 删除 `spring-dotenv` 依赖。

### 为什么 key 不写进 application.yml

- `application.yml` 会随代码提交到公开 GitHub 仓库 → 密钥直接泄露；
- `.env` 被 `.gitignore` 忽略，只存在于本机；
- 代码里只有占位符 `${DEEPSEEK_API_KEY:}`，真实值只在本机 `.env`；
- 生产环境用真正的环境变量（CI/CD 注入），代码无需改动。

### key 安全要点

- 传输：API 走 HTTPS（TLS），抓包看到的是密文；
- key 在请求头 `Authorization`（不在 body），且只存在于后端，前端 JS 不接触；
- 代码不打印请求头，避免日志泄露；
- 生产应使用密钥管理（Vault/KMS）并定期轮换。

## 2. D5-D6 模型层验证流程

### 为什么这样分层验证

1. 先验证**外部事实**（key 有效、模型名有效、响应格式长什么样）；
2. 再验证**自己的解析代码**（Mock 单测，不花钱、不依赖外网）；
3. 最后**端到端收口**（真实调用，验证配置 + .env + Bean 装配全链路）。

哪一步挂，就能立刻定位是外部问题还是自己的问题。

### 步骤 1：直接调真实 API（先证明外部事实）

用 PowerShell 从 `.env` 读取 key（不暴露明文），分别调用：

- DeepSeek chat：`POST https://api.deepseek.com/v1/chat/completions`，`model=deepseek-v4-flash`
- 硅基流动 embedding：`POST https://api.siliconflow.cn/v1/embeddings`，`model=BAAI/bge-m3`
- 硅基流动 rerank：`POST https://api.siliconflow.cn/v1/rerank`，`model=BAAI/bge-reranker-v2-m3`

验证指标（出现什么、证明什么）：

- chat 返回 `choices[0].message.content` 非空 → key 有效、模型名 `deepseek-v4-flash` 正确；
- embedding 返回 `data[0].embedding` 长度 **1024** → 与表结构 `vector(1024)` 一致；
- rerank 返回 `results[0].relevance_score ≈ 0.99`、其余 ≈ 0.001 → 排序能力正常，且确认字段名 `index` / `relevance_score` 正确。

### 步骤 2：MockWebServer 单测（验证解析代码）

命令：`mvn -pl infra-ai test`

验证指标：`Tests run: 5, Failures: 0`（chat 3 个 + embedding 1 个 + rerank 1 个）→ 解析逻辑正确，且不花钱、不依赖外网，CI 可重复跑。

### 步骤 3：真实集成测试（端到端收口）

命令：

```powershell
mvn -pl bootstrap -am test -Dtest=AiClientRealIT -DfailIfNoTests=false "-Dsurefire.failIfNoSpecifiedTests=false"
```

说明：类名以 `IT` 结尾，surefire 默认不执行 → CI 不会自动跑真实 API（避免烧钱）。

验证指标：

- `CHAT_REAL` 输出非空 → `.env` 加载 + provider 装配 + Chat 客户端全链路通；
- `EMBEDDING_REAL dim=1024` → 维度一致；
- `RERANK_REAL` 分数排序正确（相关 0.99 vs 不相关 0.001）；
- `Tests run: 3, Failures: 0, Errors: 0`。

### 步骤 4：全量单测（确认 CI 安全）

命令：`mvn -pl bootstrap -am test`

验证指标：`Tests run: 6`（infra-ai 5 + bootstrap 1），`BUILD SUCCESS` → 常规构建不触发真实 API。

### 步骤 5：重启后端确认装配

重启 jar 后：

- `GET /api/health` → `UP`；
- `GET /api/ai/providers` → 返回 `deepseek`（chat）与 `siliconflow`（embedding, rerank）两个 provider，且响应中不含 `sk-` → Bean 装配成功、无密钥泄露。

### 其他实操坑

- PowerShell 中 `-D` 参数如果带点（如 `-Dsurefire.failIfNoSpecifiedTests=false`）会被拆成多个参数，必须用引号包裹整个参数；
- 集成测试的 `user.dir` 是模块目录，`.env` 加载器必须向上查找（见第 1 节）。
