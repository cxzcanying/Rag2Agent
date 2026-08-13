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
