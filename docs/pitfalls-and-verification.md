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

## 3. D7-D8 文档入库：真实 PDF 验证与踩坑

### 真实 PDF 验证结果（PdfBoxDocumentParser + RecursiveTextSplitter）

通过环境变量 `VERIFY_PDF_PATHS`（分号分隔）指定待验证 PDF，运行：

```powershell
$env:VERIFY_PDF_PATHS="C:\path\a.pdf;C:\path\b.pdf"
mvn -pl rag-core -am test -Dtest=RealPdfVerifyIT
```

统计写入 `rag-core/logs/pdf-verify-report.txt`（注意：测试的 `user.dir` 是模块目录，不是项目根）。

实测三个中文 PDF：

| 文档 | 大小 | 页数 | 文本长度 | 中文字符 | 切块数 | 解析耗时 |
|---|---|---|---|---|---|---|
| Java 面经手册（含表格） | 16MB | 417 | 35.9 万 | 9.3 万 | 1253 | 4.1s |
| java基础讲义 | 6MB | 243 | 30 万 | 5.7 万 | 511 | 3.3s |
| 廖雪峰 python 教程 | 5MB | 208 | 26.4 万 | 7.8 万 | 1310 | 0.9s |

结论：中文提取无乱码、分页正确、切块正常；16MB 大文件解析约 4 秒可接受。
已知优化点：PDF 提取是**行级换行**（每个视觉行一个 `\n`），"段落优先"切块效果打折，后续评测阶段做文本规范化（合并单换行、保留双换行段落）。

### PDFBox 3.x API 变化（两次编译报错）

1. `Loader.loadPDF(InputStream)` 不存在——3.x 只接受 `byte[]` / `File` / `RandomAccessRead`，需先 `input.readAllBytes()` 再加载；
2. `PDType1Font.HELVETICA` 常量已移除——改为 `new PDType1Font(Standard14Fonts.FontName.HELVETICA)`。

教训：网上多数 PDFBox 教程是 2.x 的写法，3.x 迁移时按编译错误逐个修正，别凭记忆写。

### Spring Boot multipart 默认 1MB 上传限制

现象：上传 5.76MB 的 PDF 返回 500 `Maximum upload size exceeded`。

原因：Spring Boot 默认 `spring.servlet.multipart.max-file-size=1MB`。

解决：`application.yml` 配置：

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB
```

注意与业务层校验（DocumentService 50MB）保持一致。

### RocketMQ broker 在 Docker 里注册容器内网 IP

现象：Producer `send` 超时（`sendDefaultImpl call timeout`），但 namesrv 连接正常、broker 健康检查通过。

原因：broker 向 namesrv 注册的地址是容器内网 IP（如 `172.20.0.7:10911`），宿主机上的应用拿到该地址连不上。

排查：`docker exec rag2agent-rocketmq-broker sh mqadmin clusterList -n rocketmq-namesrv:9876` 看 `#Addr` 列。

解决：`docker/rocketmq/broker.conf` 加 `brokerIP1 = 127.0.0.1`（单机开发场景；多机部署需填 broker 对外 IP），然后 `docker compose restart rocketmq-broker`，再用 `clusterList` 确认地址变为 `127.0.0.1:10911`。

### 测试报告文件路径（user.dir 的第二次坑）

现象：测试写 `logs/pdf-verify-report.txt` 找不到，实际写到了 `rag-core/logs/` 下。

原因：surefire 运行时 `user.dir` 是模块目录，相对路径以模块为基准。

解决：报告路径写成相对 `user.dir` 或使用绝对路径；验证脚本里先确认文件实际落点。

### Windows 保留端口范围（Docker 端口绑定失败）

现象：PostgreSQL 容器映射宿主端口 5432、5433、5439 全部失败，报错
`Ports are not available ... bind: An attempt was made to access a socket in a way forbidden by its access permissions`；
其余端口（6379/9000/9876 等）正常。

原因：Hyper-V / WSL2 会动态保留一段 TCP 端口范围（`netsh interface ipv4 show excludedportrange protocol=tcp` 可查），
Windows 重启后保留范围会变化；个别端口（如 5439）不在列表却也绑定失败，属于 Docker Desktop 端口转发层的偶发问题。

排查：

```powershell
netsh interface ipv4 show excludedportrange protocol=tcp
netstat -ano | findstr ":5439"
```

解决：把 PostgreSQL 宿主映射改为不常见端口 `15432:5432`，并同步修改 `application-dev.yml` 数据源 URL。
教训：Windows + Docker Desktop 下不要死磕"标准端口"，换高位不常见端口最省事；这个坑与代码无关，属环境问题。

## 4. D8-D10 检索链路：混合检索、RRF、Rerank 与踩坑

### 实现范围

- 向量检索：pgvector 余弦相似度，SQL 里 `1 - (embedding <=> CAST(? AS vector))`；
- 关键词检索：pg_trgm 的 `similarity` + `ILIKE` 模糊包含；
- 融合：RRF（Reciprocal Rank Fusion），k=60，同名 chunk 跨路累加去重；
- 精排：bge-reranker-v2-m3 cross-encoder；
- 路由：规则版 QueryRouter（疑问句→语义，短词/编号→关键词，其余→混合）；
- 引用溯源：RetrievalResult.metadata 带 documentId / chunkIndex；
- 权限边界：SQL 按 kb_id 过滤；版本正确性靠 `c.version = d.version` join 只查当前版本。

验证：查询"Python 的数据类型有哪些"，命中廖雪峰教程"1.1 数据类型和变量"，score≈0.99，溯源 documentId/chunkIndex 正常。

### 坑 1：Windows 保留端口范围是动态的，会"吃掉"新端口

现象：后端反复报 `Port 8080/8081 already in use`，但 `netstat` 查不到任何本地 LISTEN 进程。

根因：Hyper-V/WSL 的保留端口范围会随重启**动态变化**。这次重启后范围变成 `7989-8088`，正好覆盖 8080/8081；
之前查 `excludedportrange` 时 8080 不在范围，所以容易被误导成"有隐藏进程占端口"。

排查命令：

```powershell
netsh interface ipv4 show excludedportrange protocol=tcp
netstat -ano | findstr ":8080"
```

解决：后端端口改到高位 `18080`（application.yml + vite proxy 同步），并意识到这个坑和 Docker 端口绑定失败是同一根因。
经验：报"端口占用但查不到进程"先看 excludedportrange，别死磕进程排查。

### 坑 2：pg_trgm 需要手动装扩展

现象：关键词检索 SQL 用 `similarity()` 时报函数不存在。

原因：pgvector 在 init SQL 里建了，但 pg_trgm 没建。

解决：运行库执行 `CREATE EXTENSION IF NOT EXISTS pg_trgm;`，并同步补进 `docker/postgres/init/001_extensions.sql`（新环境自动装）。

### 坑 3：Rerank 低分候选仍会被返回

现象：RRF 融合后取 topK 交给 rerank，rerank 只返回前 topK 个，即使绝对分数很低（如 0.0045）也会作为最后一名返回。

说明：这是召回质量的上游问题——融合阶段能进入候选的就不够好，rerank 只是精排不改召回。
改进方向（评测阶段）：加 rerank 分数阈值过滤；优化关键词路的中文分词（pg_trgm 对中文效果弱，考虑 zhparser/ES）。

### 坑 4：Neo4j 端口同样中招（7474/7687）

现象：`docker compose up` 时 Neo4j 的 7474 端口绑定失败，容器一直没起来；
`netsh show excludedportrange` 里也查不到 7474，和之前 5439 的情况一致（不在保留列表却也绑不上）。

解决：Neo4j 宿主端口改为高位 `17474:7474`、`17687:7687`（docker-compose.yml），容器恢复 healthy。

结论：这台机器的 Windows 端口绑定有"不在保留范围也失败"的怪症，凡是宿主映射端口都优先选高位（15xxx/17xxx/18xxx），不要死磕标准端口。

## 4. 入库幂等：从"先删后插"到"版本化快照"（技术选型记录）

### 问题来源：消息一定会重复

RocketMQ 是 at-least-once 语义，消息重复投递有三个现实来源：

1. 消费失败返回 `RECONSUME_LATER`，消息按退避间隔重投——而且 `MessageListenerConcurrently` 是批量回调，一条失败会连累整批重投；
2. 网络抖动导致 RocketMQ 没收到成功确认，重复发送；
3. 应用处理到一半被重启，消息仍留在队列，又要重跑。

原实现直接 INSERT `document_chunk`，重复执行会**重复插入 chunk**，后果是检索命中重复内容、库数据膨胀、embedding 重复花钱。

### 三个候选方案的权衡

方案 1：事务里先删后插（整体替换）

- 优点：最简单彻底，重复跑多少次最终都只有一份数据，语义清晰；
- 缺点：大文档（上千 chunk）全量删除+重插成本高；事务期间旧数据被清空产生窗口期；长事务持锁。

方案 2：唯一约束 `(document_id, chunk_index)` + `ON CONFLICT` upsert（增量更新）

- 优点：按 chunk 粒度幂等，无需全量删除，适合频繁更新与大数据量；
- 缺点：依赖 chunk_index 稳定。当切块参数调整、文档更新或 embedding 模型升级导致新旧 chunk 集合数量/边界对不上时，**多余旧 chunk 删不掉**，必须补一个"删除不在新集合中的 chunk"的收尾步骤，复杂度回升；
- 语义问题：chunk 是从文档派生的数据，文档一变就该整体换代，"局部更新"与之不匹配。

方案 3：版本化快照（蓝绿切换）

- 核心：每次入库生成新版本 `v = 旧版本 + 1`；新 chunk 全部带 `version=v` 写入；写完后在一个事务里把 `document.version` 切到 `v` 并删除 `< v` 的旧 chunk；检索只认当前版本。
- 优点：一次解决幂等、文档更新、无窗口期三个问题——切换前旧版本完整可读，切换后新版本完整可读；
- 缺点：多一个 version 字段的管理成本，实现比方案 1 稍复杂。

### 最终选型：方案 3 + "已完成跳过"

选方案 3 的理由：chunk 是文档的派生数据，整体换代才是匹配语义；项目 `document` 表本就预留了 `version` 字段；它同时覆盖了文档将来更新、重入库的需求。

落地细节：

1. `document_chunk` 增加 `version INT NOT NULL DEFAULT 1` 列与 `(document_id, version)` 索引（[002_schema.sql](docker/postgres/init/002_schema.sql)）；
2. 写入前先删本次版本的残留（上次失败重投可能写了一半），再写新 chunk（[DocumentChunkMapper.java](../bootstrap/src/main/java/com/rag2agent/bootstrap/mapper/DocumentChunkMapper.java) 的 `deleteByDocumentAndVersion`）；
3. 版本切换放在数据库事务里：`document.version = v` 与删除旧版本 chunk 原子完成（[IngestPipelineService.java](../bootstrap/src/main/java/com/rag2agent/bootstrap/service/IngestPipelineService.java) 的 `switchVersion`，用 `TransactionTemplate` 避免同类内调用事务失效）；
4. 任务已 `INDEXED` 时直接跳过重复消费，避免确认丢失导致的重复 embedding 开销；
5. 检索阶段必须按 `document.version` 过滤 chunk（待检索链路实现时落地），确保只读当前版本。

### 验证方式与结果

`IngestPipelineIdempotentIT`（IT 结尾，不进 CI）用 document_id=5 的 511-chunk 文档验证两个场景：

- 任务已完成、消息重复投递 → 跳过，chunk 数量与版本均不变；
- 任务改为 FAILED 模拟"处理一半失败后重投" → 重跑后 chunk 数量不变（整体替换）、版本 +1、旧版本 chunk 清理干净（仅剩 1 个版本）。

结果：`Tests run: 1, BUILD SUCCESS`。

### 遗留与演进

- 方案 3 已在写入侧落地，检索侧"按当前版本过滤"待实现；
- 文档更新/重新入库接口未来做（现在每次上传是新 document）；
- 单文档 chunk 达数千级、全量重插变慢时，可再评估方案 2 的 upsert 作为增量优化，但语义上仍是"版本整体替换"。

## 5. D11 函数调用（function calling）真实验证

> 验证时间 2026-08-17。用 PowerShell 直连 DeepSeek（`deepseek-v4-flash`），工具声明 `delete_document(document_id:integer)`，问题"请帮我删除文档 123"。

### 结论：支持 function calling，且有两个实操必知点

非流式：`finish_reason=tool_calls`，`message.tool_calls[0].function.arguments` 是 JSON **字符串** `{"document_id":123}`（不是对象）。

流式返回两个关键事实：

1. **`delta.reasoning_content` 独立字段**：DeepSeek 会在 `content` 之外先流式输出一段推理文本（本例是 "The user asks to delete document 123. Let me delete it."），放在 `reasoning_content` 里逐片返回，此时 `content` 为 null；最后的 `usage` 里也单列了 `reasoning_tokens`。如果解析器只读 `delta.content`，会完全丢推理内容，甚至不知道还有这个字段。
2. **`tool_calls` 的 `arguments` 跨 chunk 分片**：第一个 tool_calls 分片带全 `index/id/type/function.name`，`arguments` 为空串；后续分片只带 `function.arguments` 的碎片（`{`、`"document_id"`、`: `、`123`、`}`），需要按 `index` 分组、按顺序累加拼接成完整 JSON 再解析。

### 对实现的影响

- 流式解析必须支持 `tool_calls` 分片聚合（按 `index` 归组、`arguments` 累加），不能只处理纯文本 `content`。
- 需要决定 `reasoning_content` 是否透出：透出则前端可展示思考过程，但会多一类事件与成本；不透出则忽略该字段，只取 `content`。
- function calling 循环：模型返回 `tool_calls` → 执行工具 → 结果以 `role:tool` + `tool_call_id` 回传 → 模型继续，直到 `finish_reason=stop`。

## 6. D11 Agent 阶段：RocketMQ 9876 端口也被保留

现象：Docker Desktop 重启后 `docker compose up -d`，RocketMQ namesrv 绑定失败，报 `Ports are not available ... 9876`，broker 因 `depends_on namesrv healthy` 也跟着起不来。

原因：`netsh interface ipv4 show excludedportrange protocol=tcp` 显示保留区间 `9849-9948`，9876 正好落在里面。这是第 4 节坑 1 / 坑 4 同一根因的延续——保留区间随重启动态变化，这次轮到了 9876。

解决：docker-compose 里 namesrv 宿主端口改 `19876:9876`，`.env` 加 `ROCKETMQ_NAMESRV=localhost:19876`（producer/consumer 的 `@Value` 默认值是 `localhost:9876`）。broker 通过容器内网连 `namesrv:9876`，不受影响。

教训：这台机器凡是"标准端口"都可能随时被保留区间吃掉，宿主映射一律优先选 15xxx/17xxx/18xxx/19xxx 高位端口。
