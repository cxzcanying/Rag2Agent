# V2 集成验收记录

更新时间：2026-09-01（Asia/Tokyo）

本文记录本地完整拓扑上的真实验证结果。真实账号和模型 API key 均未记录；MCP 仅使用文档中明确的开发测试 token，所有请求使用临时测试账号和脱敏参数。

## 0. 结论摘要

按第二版七天计划，D1-D7 的核心验收项已完成：异步评测可靠性、上下文预算、HTTP/MQ/Jaeger 链路、限流与恢复策略、远程 MCP HTTP 子集、真实成本账本和故障演练均有实现与运行证据。当前验证基线为后端 Maven 全 reactor `74/74` 通过（infra-ai 15、rag-core 14、bootstrap 45），前端生产构建通过、全量 Compose 服务 healthy。

尚未宣称完成的内容是生产规模评测数据（需脱敏生产文档和业务复核的 50-100 条金标）、完整官方 Streamable HTTP 会话协商、GraphRAG 和多租户产品化能力；这些不影响本地 V2 闭环验收结论。

## 1. 运行环境

执行：

```powershell
# 若要复现本次中文分词环境，先构建并选择自定义 PG 镜像；否则可使用默认 pgvector/pg17
docker build -t rag2agent-postgres:pg17-zhparser docker/postgres
$env:RAG2AGENT_POSTGRES_IMAGE="rag2agent-postgres:pg17-zhparser"
$env:RAG2AGENT_MQ_MAX_RECONSUME_TIMES="2"
docker compose --profile full up -d
mvn -pl bootstrap -am -DskipTests package
# 另开终端启动 MCP HTTP 服务（Compose 不包含此服务）
mvn -pl mcp-server spring-boot:run
java -jar bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar `
  --rag2agent.ingest.rocketmq-enabled=true `
  --rag2agent.mcp.remote.enabled=true `
  --rag2agent.mcp.remote.token=dev-mcp-token
```

健康结果：PostgreSQL（自定义 `rag2agent-postgres:pg17-zhparser`）、Redis、MinIO、RocketMQ、Neo4j、Jaeger 均为 healthy；`GET http://localhost:18080/api/health` 返回 `UP`。Jaeger UI 为 `http://localhost:16686`，MCP 服务为 `http://localhost:19090/mcp`。

## 2. RocketMQ 到 Jaeger

1. 通过文档上传接口创建异步入库任务，消息进入 `INGEST_TOPIC`。
2. producer 创建 `rag2agent.mq.producer` Observation，并将 W3C `traceparent/tracestate` 写入消息属性。
3. consumer 提取消息上下文，创建 `rag2agent.mq.consumer` Observation。
4. Jaeger 查询验收样例的同一 `traceID`，consumer span 的 references 包含 producer span；该样例中 HTTP 上传、producer、consumer 构成真实异步父子链路。重试或脱离 HTTP 入口的历史 consumer trace 可能没有 HTTP 父 span。

结论：真实 MQ trace context 传播和父子关系通过。

## 3. MCP 网络、认证和远程权限

请求方法为 JSON-RPC HTTP `initialize`、`tools/list`、`tools/call`：

| 场景 | 结果 |
|---|---|
| 无 `Authorization` | HTTP 401 |
| Bearer token + 请求 scope 不满足要求 | HTTP 401 |
| Bearer token + `mcp:read` + `initialize` | HTTP 200，返回 protocol/serverInfo |
| `tools/list` | 返回远程 `echo` 工具及 schema |
| `tools/call echo` | HTTP 200，返回 `remote-ok` |
| `tools/call` 未授权工具 | HTTP 403 |

当前服务范围是项目内 JSON-RPC HTTP 子集；开发验收使用静态 Bearer token，token 授予 scope 由服务端配置，`X-MCP-Scopes` 仅声明本次请求所需 scope，已覆盖真实网络传输、认证和 scope 权限校验，但不代表生产 JWT/OAuth 身份系统；尚未声称完整官方 Streamable HTTP 会话协商兼容。

## 4. 入库锁、重试、重平衡和死信

### 4.1 消息重试和死信

使用 broker 内置管理命令投递非法文档消息：

```powershell
docker exec rag2agent-rocketmq-broker sh mqadmin sendMessage `
  -n rocketmq-namesrv:9876 -t INGEST_TOPIC `
  -p '{"documentId":999999,"taskId":null}'
```

应用设置 `RAG2AGENT_MQ_MAX_RECONSUME_TIMES=2`。结果：同一消息在 attempt `0/1/2` 各失败一次，`rag2agent.mq.consume.retries` 计数为 3；主题 `%DLQ%rag2agent-ingest-consumer` 出现 1 条消息，`topicStatus` 的 max offset 为 1。

以上 offset 是 2026-09-01 演练时的快照；后续演练会继续累加，不能将 max offset 当作固定系统常量。

### 4.2 死信恢复

```powershell
docker exec rag2agent-rocketmq-broker sh mqadmin consumeMessage `
  -n rocketmq-namesrv:9876 -t '%DLQ%rag2agent-ingest-consumer' `
  -g dlq-drill-reader -c 1
```

命令成功读取原始消息（含 `ORIGIN_MESSAGE_ID`、`RETRY_TOPIC` 和消息体）。将修正后的消息重新投递到 `INGEST_TOPIC` 后，消费者正常接收；该动作验证了 DLQ 的读取和回投恢复入口。

回投是人工恢复动作：从 DLQ 读取并修正文档 ID/任务字段后，使用同一个 `mqadmin sendMessage` 命令将修正后的 JSON 投回 `INGEST_TOPIC`，再以消费者日志和数据库任务状态确认恢复。

### 4.3 消费者重平衡

启动两个端口不同、consumer group 相同的 bootstrap 实例：`18080` 和 `18081`。向 `INGEST_TOPIC` 投递多条消息后，两个实例分别出现 `ConsumeMessageThread_rag2agent-ingest-consumer_*` 消费日志，消息分配到不同 queue；第二实例加入后发生队列重新分配，实例停止后剩余实例继续消费。

### 4.4 入库锁

对已索引文档 `418` 预置短 TTL Redis 锁并投递重复消息：

```powershell
docker exec rag2agent-redis redis-cli SET rag2agent:ingest:lock:418 expiry-drill EX 8
docker exec rag2agent-rocketmq-broker sh mqadmin sendMessage `
  -n rocketmq-namesrv:9876 -t INGEST_TOPIC -p '{"documentId":418}'
```

投递时 Redis `TTL` 为 8 秒；等待 10 秒后查询为 `-2`（key 已自然过期），再次投递同一消息，文档 `418` 保持 `INDEXED`、version `2`。本次快照的 Actuator 指标为 `operation=acquire`：`conflict=1`、`success=2`，证明锁冲突、TTL 自然过期和后续恢复路径可用。

## 5. SSE 增量续传

客户端对真实 `POST /api/chat` 读取到 `trace` 和 `run` 事件后主动 abort，记录断点 `runId=26`、`Last-Event-ID=1788257671672-0`。随后请求：

```http
GET /api/agent/runs/26/events
Last-Event-ID: 1788257671672-0
```

服务端只返回后续事件 ID `1788257672513-0` 和 `1788257678373-0`，不重复断点事件。实时 trace、run、reference、done 和 error 事件统一写入 Redis Stream；超出保留窗口时仍可通过 run 状态接口获得最终结果。

## 6. 真实价格账本

价格来源：

- DeepSeek 官方价格页：<https://api-docs.deepseek.com/quick_start/pricing>
- SiliconFlow 官方价格页：<https://siliconflow.cn/pricing>

配置策略：DeepSeek 使用官方 off-peak 单价（每 1M token，USD），工作日 UTC `01:00-04:00`、`06:00-10:00` 为 peak，自动乘 2；周末保持 off-peak。当前配置为：

| 模型 | 输入（off-peak） | 缓存输入 | 输出 | 价格版本 |
|---|---:|---:|---:|---|
| `deepseek-v4-flash` | $0.22 | $0.007 | $0.66 | `deepseek-official-2026-09-01-usd` |
| `deepseek-v4-pro` | $0.66 | $0.022 | $1.98 | `deepseek-official-2026-09-01-usd` |
| `BAAI/bge-m3`（SiliconFlow） | 免费 | 免费 | 不适用 | `siliconflow-official-2026-09-01-free` |
| `BAAI/bge-reranker-v2-m3`（SiliconFlow） | 免费 | 免费 | 不适用 | `siliconflow-official-2026-09-01-free` |

真实账本记录（2026-09-01 11:42 UTC）：

| provider/model | prompt | completion | total | estimated prompt | cached prompt | prompt cost | completion cost | total cost | currency | price version |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|
| `deepseek/deepseek-v4-flash` | 2639 | 942 | 3581 | 2166 | 384 | 0.0004987880 | 0.0006217200 | **0.0011205080** | USD | `...-off-peak` |

账本表为 `ai_usage_ledger`，同时保存供应商 usage、字符估算 token、缓存 token、价格版本和币种；价格未配置时只记录 token，不伪造金额。`TokenCostPropertiesTest` 覆盖高峰、非高峰和周末计算。

## 7. 本地冒烟金标集

金标集文件：`eval-data/v2-local-gold/cases.local.json`，说明见 `eval-data/v2-local-gold/README.md`。样本来自 `C:\Users\21311\Desktop\笔记` 的两份 PDF，独立知识库为 `kbId=8`，文档映射为 `419/420`，共 4 条人工可核对问题。

| Run | 策略 | 样本数 | 状态 | Hit@2 | MRR |
|---:|---|---:|---|---:|---:|
| 17 | VECTOR | 4/4 | COMPLETED | 1.000 | 1.000 |
| 18 | KEYWORD（二元切分回退） | 4/4 | COMPLETED | 0.000 | 0.000 |
| 19 | HYBRID + rerank | 4/4 | COMPLETED | 1.000 | 1.000 |

该数据集是研发冒烟集，不代替生产评测。生产上线前应使用脱敏生产文档重新建立金标，并固定数据版本、文档映射和答案复核人。
