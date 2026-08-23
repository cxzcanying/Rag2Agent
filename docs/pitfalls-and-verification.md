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

## 7. D11 联调：JSONB 列不能直接存非 JSON 文本

现象：对话一问就断流，后端日志报 `ERROR: invalid input syntax for type json, Token "关于内卷的看法" is invalid`。

原因：`agent_step.input` / `tool_call.output` 是 JSONB 列，AgentRunService 落库时直接把用户 query 和工具返回的纯文本塞进去，SQL 里 `CAST(#{input} AS jsonb)` 对非 JSON 文本直接报错，SSE 流中断。

解决：落库前统一 `toJson(...)`——query 序列化成 `"关于内卷的看法"`，工具输出也包成 JSON 字符串；错误信息走 TEXT 类型的 `error_message` 列。

教训：JSONB 列只能存合法 JSON，任何要入 JSONB 的值都要先序列化，别想当然塞原始文本。

## 8. D11 联调：Redis 6379 被 Windows 系统服务抢占

现象：后端连 Redis 报 `Unable to connect to localhost:6379` / `Connection reset`，但 `docker exec redis-cli ping` 返回 PONG、容器 healthy。

排查：`netstat -ano | findstr ":6379"` 发现两个进程监听 6379——com.docker.backend（Docker 转发）和 svchost（PID 6916，Windows 系统服务）；TCP 直连 127.0.0.1:6379 发 Redis PING 无响应，说明宿主端口被系统服务抢占，后端连到的是它而不是 Redis。

解决：Redis 宿主端口改 `16379:6379`，并同步改 `application-dev.yml` 的 `spring.data.redis.port`（dev profile 会覆盖 application.yml，只改主配置不生效）。

教训：排查"容器 healthy 但应用连不上"先看宿主端口是不是真被本机其他进程占着；`application-dev.yml` 里写死的端口优先级高于主配置。

## 9. D11 联调：SseEmitter 用法不当导致 SSE 流被"掐断"

现象：后端 run 已 COMPLETED、SSE 事件（reference/done）都发出去了，但浏览器 fetch 一直挂起（输入框持续 loading、不出答案），curl -N 却正常。

排查：用 Node fetch（undici，与浏览器 fetch 行为一致）直连和走 vite 代理都能复现——事件收得到，但流不结束，报 `TypeError: terminated` / `SocketError: other side closed`。根因是 SseEmitter 用法错误：controller 在**返回 emitter 之前**就同步执行完整个 Agent 循环并调用 `complete()`，响应头还没提交就结束了异步处理，Tomcat 直接关闭连接，客户端拿不到正常的 chunked 终止块，fetch 把连接关闭当成异常而不是正常 EOF。

解决：弃用 SseEmitter，改用 `HttpServletResponse` + `PrintWriter` 手动写 SSE——每个事件写 `event:` / `data:` 行并 `flush()`，方法正常返回后 Tomcat 发送完整的流终止信号。虚拟线程下同步阻塞式编排完全可行。

教训：SseEmitter 的正确用法是"先返回 emitter，再在其他线程里 send/complete"；同步阻塞式编排不要用 SseEmitter，直接写响应流更可控，也更容易排查。

## 10. D11 联调：无引用不答会误拦工具操作请求

现象：用户输入"删除文档 8"直接返回"未找到相关资料，无法回答该问题"，没有触发 delete_document 审批弹窗。

原因：`AgentRunService.start` 在主动检索结果为空时直接 return"未找到"，模型根本没机会进入 function calling 循环。但"删除文档 8"本来就不该从知识库检索到内容——它是一次工具操作，不是问答。

解决：检索为空时不再直接拒绝，而是把"检索结果为空"作为上下文交给模型，让它自己决定：删除/修改等操作类请求直接调用对应工具，问答类请求如实回答"未找到"。system prompt 同步强调"即使检索结果为空，用户要求删除文档也要调用 delete_document"。

教训："无引用不答"防的是模型编造答案，但不应阻止工具调用；RAG 规则要区分"问答"和"工具操作"两类意图，不能一刀切。

## 11. D13 公开评测集联调：语料控制字符、PDF 字体与断点续跑

> 验证时间 2026-08-19。使用 MIRACL 中文 dev（Apache-2.0，固定 revision）和中文 corpus 第 0 分片构造 100 条检索用例、339 篇文档。

### 本项目一次评测的完整过程

评测不是“上传几份文档后调用一个接口”这么简单，而是一条有固定输入、异步任务和数据库结果的流水线。每次实验都必须保持数据集、知识库、检索配置和结果之间可追溯：

1. **固定数据集和实验输入**：下载指定 revision 的原始数据，记录 revision、文件 SHA256、抽样种子和用例/文档数量。`prepare_miracl_zh.py` 根据 topics、qrels 映射出问题和金标文档，并加入干扰文档；`prepare_dureader_robust.py` 从带参考答案的段落生成问题、答案和金标文档。脚本输出 `documents.jsonl`、`cases.external.json`、`manifest.json`，后续生成供系统上传的 PDF。先用 PDFBox 或 `pdfplumber` 抽样提取文本，确认中文、特殊字符和控制字符正常。
2. **启动并核对运行环境**：启动 PostgreSQL/pgvector/pg_trgm、Redis、MinIO、RocketMQ 以及后端，确认健康检查、端口和数据库评测表已就绪。已有数据库卷需要手动执行 `docker/postgres/init/003_evaluation.sql`；新卷由初始化脚本自动创建。评测脚本先注册或读取专用账号，再登录取得 `satoken`，避免使用个人业务数据和凭证。
3. **创建或恢复独立知识库**：运行器从 `run-state.json` 读取 `kbId`；没有时创建新的知识库并立即保存。不同数据集、不同切块参数或不同索引版本使用独立知识库，不能在同一知识库中原地覆盖，否则无法判断结果差异来自数据还是配置。
4. **批量上传并等待索引完成**：逐个调用 `POST /api/documents/upload?kbId={kbId}` 上传 PDF，保存 `externalDocumentId -> documentId` 映射。上传接口只代表任务创建，必须轮询 `GET /api/documents?kbId={kbId}`，直到所有文档为 `INDEXED`；`FAILED` 文档按记录重传或调用 reingest。每次上传、重试和状态变更都写入断点文件，脚本重启时跳过已成功项，避免重复文档和 embedding 费用。
5. **导入评测用例并完成 ID 映射**：将 `cases.external.json` 中的金标外部文档 ID 替换成上传后得到的内部 `documentId`，调用 `POST /api/evaluations/cases/import` 写入当前知识库。导入前核对用例数量、每题至少一个金标文档（生成评测还要有 `expectedAnswer`），并确认用例没有混入其他知识库。
6. **提交一个或多个评测运行**：为每组实验固定 `strategy`（`VECTOR`/`KEYWORD`/`AUTO`）、`topK`、`candidateTopK`、`rrfK`、`rerankEnabled` 和 `evaluateGeneration`。单组调用 `POST /api/evaluations/runs`，配置矩阵调用 `/api/evaluations/matrix`；请求带稳定的 `Idempotency-Key`。接口应立即返回 `runId`，后台再执行，网络超时后先查询原 `runId`，不要盲目重新提交。
7. **后台逐题执行并持久化**：每道题先按配置路由检索，向量/关键词结果经过候选截取、RRF 融合和可选 Rerank，依据金标文档计算首个相关排名。开启 `evaluateGeneration` 时，再把检索上下文交给回答模型生成答案，并交给裁判模型计算 Faithfulness 和 Answer Correctness。每题结果写入 `eval_case_result`，运行进度和聚合指标写入 `eval_run`；单题错误必须保留错误信息，不得用整批 HTTP 失败掩盖。
8. **轮询运行状态并处理终态**：按 `runId` 调用 `GET /api/evaluations/runs/{runId}`，观察 `status`、`completedCases`、`totalCases` 和错误信息，直到 `COMPLETED`、`COMPLETED_WITH_ERRORS` 或 `FAILED`。评测任务可能跨越客户端超时或应用重启，恢复时继续查询数据库中的运行，不以某个脚本进程是否存活作为完成依据。
9. **对账、统计和解释结果**：终态后以 `eval_run` / `eval_case_result` 为事实源，核对总用例数、成功数、失败数和逐题排名，再报告 Hit@5、MRR；只有开启生成评测且结果完整时才报告 Faithfulness、Answer Correctness。比较实验时保持同一数据集、知识库文档集合和用例顺序，只改变一个待验证配置，并同时记录 runId、配置、数据集 revision 和脚本 manifest。
10. **保存可复现实验凭证**：最终报告至少包含数据集版本和 SHA256、知识库 ID、文档/用例数、每个 Run ID、完整配置、终态、指标、失败用例和运行时间。`run-state.json` 只用于上传断点和脚本恢复，不能替代数据库结果，也不能单独作为“评测已完成”的证据。

### 坑 1：公开语料的 NUL 控制字符会击穿 PostgreSQL 入库

现象：339 篇 PDF 全部上传成功，但部分异步任务 FAILED。`ingest_task.error_message` 报：

```text
ERROR: invalid byte sequence for encoding "UTF8": 0x00
INSERT INTO document_chunk ...
```

原因：第一轮判断为 MIRACL 原文含 NUL，进一步对比原始 JSON、`pdfplumber` 与 PDFBox 后确认：原文没有 NUL；主中文字体缺少 `♍`、`Š` 等字符，ReportLab 生成了错误的 Unicode 映射，解析器最终得到 `\u0000`。PostgreSQL 的 `text` 类型拒绝 NUL。

解决：生成 PDF 时使用 Noto Sans SC 作为主字体、Segoe UI Symbol 作为回退字体；若两者都不支持某字符，显式写成 `[U+XXXX]`，保证信息可追踪。重新生成并上传失败文档；不要修改数据库编码或吞掉入库异常。

教训：公开数据“有许可证”不等于“满足数据库字符约束”。导入前至少做控制字符、超长文本和 UTF-8 校验；失败必须可重入，不能靠跳过坏样本伪造评测完成。

### 坑 2：ReportLab CID 字体能显示中文，但可能导致文本提取乱码

现象：第一版用 `UnicodeCIDFont("STSong-Light")` 生成的 PDF，视觉上能显示中文，但 `pdfplumber`/PDFBox 提取结果是乱码，检索内容因此不可用。

原因：CID 字体没有提供当前工具链可稳定消费的 Unicode ToUnicode 映射；仅换成单个 TrueType 中文字体仍不够，字体缺失的少数字符也可能被解析成 NUL。

解决：嵌入 TrueType 中文字体（本机 `NotoSansSC-VF.ttf`）并增加符号字体回退；同时做两项验证：文本提取必须保留中文且不含 NUL；抽样页面渲染必须无缺字、无重叠、无截断。

教训：PDF 评测数据不能只看截图“能不能显示”，必须验证“解析器能不能还原文本”。生成 PDF 后先走 PDFBox/`pdfplumber` 提取，再进入入库链路。

### 坑 3：评测批量上传不能设计成一次性脚本

现象：339 个文档上传过程中，已有 121 个完成 INDEXED，部分仍在异步处理；若脚本失败后从头上传，会产生重复文档和重复 embedding 成本。

解决：运行器把 `externalDocumentId -> documentId`、知识库 ID、导入状态和 run 报告写入被 Git 忽略的 `eval-data/.../run-state.json`；重启后跳过已上传项，只轮询状态，对 FAILED 项调用 reingest。

教训：D13 评测本身也是长流程任务，要具备幂等、断点续跑、失败重入和最终状态核对。评测工具不应比业务链路更脆弱。

### 坑 4：内置 Poppler 包装脚本路径可能失效

现象：`pdftoppm` 命令已在 PATH，但包装脚本指向不存在的 native Poppler 路径，渲染时报“找不到路径”。

解决：改用本地 PyMuPDF 完成同等页面渲染检查，并记录渲染工具版本；这只替代验证工具，不改变 PDF 内容或业务结果。

教训：评测环境依赖也要做可执行性检查。遇到包装脚本失效时，优先使用等价的本地库验证，避免把工具链故障误判成应用故障。

### 坑 5：生成评测同步接口超过客户端读取超时

现象：调用 `POST /api/evaluations/runs` 开启 `evaluateGeneration=true` 时，客户端等待 120 秒后抛出 `requests.ReadTimeout`；但后端没有停止，数据库中的同一个 `eval_run` 最终变为 `COMPLETED`，逐题结果也全部持久化。

原因：当前接口是同步阻塞请求。每道题至少包含一次回答模型调用和一次裁判模型调用，30 条用例的总耗时超过客户端读取超时；HTTP 客户端断开并不会自动取消服务端线程或数据库事务。

处理：先查询已有 `runId` 的状态再决定是否重试，不能因为客户端 timeout 就重复提交同一批评测，避免重复消耗模型额度和产生重复运行记录。

后续方案：将评测提交改为异步任务，立即返回 `runId`，补充状态/进度查询接口；前端和脚本采用轮询或 SSE 获取进度，并允许显式取消。短期运行脚本应使用足够长的读取超时。

#### 为什么评测任务必须从同步改为异步

这不是单纯把客户端超时调大，而是要把 HTTP 请求生命周期与评测任务生命周期解耦：

1. **评测耗时不可由接口稳定上界控制**：检索、回答模型和裁判模型的耗时会随用例数量、模型排队、429 重试和网络抖动变化。同步接口把整批任务绑定在一个 HTTP 连接上，客户端、网关或代理任一处超时，都会让调用方误以为任务失败。
2. **客户端断开不会可靠取消服务端工作**：请求超时后，服务端线程可能仍在调用模型并写入 `eval_case_result`。客户端若立即重试，同一批用例可能产生重复运行、重复模型费用和重复副作用。
3. **异步接口可以提供可恢复的状态机**：提交只负责创建或复用 `eval_run` 并返回 `runId`；后台任务负责逐题执行、持久化结果和更新 `QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED` 状态。进程重启后可从数据库恢复未完成运行，而不是依赖原始 HTTP 连接。
4. **进度和错误可以被准确表达**：调用方可以按 `runId` 查询 `totalCases/completedCases`、失败原因和最终指标；单题失败可记录在 `eval_case_result.error_message`，不必把整批任务粗暴归类成 HTTP 500。
5. **幂等边界更清晰**：提交请求使用 `Idempotency-Key` 或客户端请求 ID，数据库中的 `eval_run` 是全局事实源；网络重试只复用已有运行，不重新消耗模型额度。

异步化并不意味着“后台丢任务”。最低验收要求是：提交接口在短时间内返回 `runId`；任务状态和逐题结果持久化到数据库；服务重启后能够恢复 `QUEUED/RUNNING` 运行；相同幂等键不会创建第二个 `eval_run`；前端或脚本通过轮询/SSE 等方式等待终态。`run-state.json` 只能做脚本断点辅助，不能替代 `eval_run` / `eval_case_result`。

### 坑 6：评测运行状态文件不能作为数据库事实来源

现象：MIRACL 自动运行器的 `run-state.json` 能记录 Run 5-7 以及上传映射；DuReader 的 Run 8 是通过其他入口完成的，数据库中已是 `COMPLETED`，但本地对应的 `run-state.json` 仍显示 `runs=[]`。如果只看状态文件，会误判生成评测没有执行。

原因：状态文件是某个脚本实例的断点上下文，不是全局评测账本；通过页面、手工 API 或另一脚本创建的 `eval_run` 不会自动回写到它。

处理：评测完成核验必须以数据库 `eval_run` / `eval_case_result` 为准，统一通过 `GET /api/evaluations/runs?kbId=...` 对账；状态文件只用于上传幂等和断点续跑。运行器恢复时应按 `runId` 检查已有记录，避免重复提交和重复消耗模型额度。

教训：长流程工具的本地状态可以丢失或过期，业务数据库才是结果事实源；脚本状态与数据库状态不一致时，不能用脚本状态覆盖数据库结论。
