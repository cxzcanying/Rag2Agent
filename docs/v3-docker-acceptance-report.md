# V3 Docker 企业级验收报告

> 验收日期：2026-10-22
>
> 环境：Windows、Java 21、Docker 25.0.3、Docker Compose 2.24.6

## 1. 验收结论

核心链路已在真实中间件环境完成端到端验收：

- 8 个 Compose 服务全部健康。
- PostgreSQL 迁移 `003-015` 全部成功。
- Maven reactor 测试 `140/140` 通过，无失败、无跳过。
- PDF、HTML、PNG 和双页 PDF 均完成入库。
- 跨页表格合并、表头绑定、页码和相邻行扩展通过真实样例验证。
- 图片查询在云端 Vision Key 未配置时自动降级到 Docling OCR，并命中已入库图片。
- GraphRAG 路径进入混合检索 RRF，Neo4j GDS `2.13.12` 社区发现和摘要完成。
- Coordinator、并行 Analyst、Reviewer 多 Agent 流程完成真实回答。
- 租户可见性、成员只读权限和写拒绝通过跨用户验证。
- 五类 Gold Set 覆盖率和发布门禁字段通过。

## 2. 服务健康

| 服务 | 验收结果 |
|---|---|
| PostgreSQL + pgvector + pg_trgm + zhparser | healthy；zhparser `2.4`；中文分词 SQL 正常 |
| Redis | healthy；`PONG` |
| MinIO | healthy；`mc ready local` 成功 |
| RocketMQ namesrv + broker | healthy；broker 已注册到 `DefaultCluster` |
| Neo4j + GDS | healthy；GDS `2.13.12` |
| Docling Serve CPU | healthy；`/ready` 返回 200 |
| Jaeger | healthy；服务 API 返回 200 |
| Bootstrap 后端 | `actuator/health=UP`；DB、Redis、Neo4j 均 UP |

PostgreSQL 使用项目自定义镜像 `rag2agent-postgres:pg17-zhparser`，避免扩展目录存在但共享库缺失。

## 3. 数据库迁移

按顺序执行 `003_evaluation.sql` 至 `015_gold_set.sql`，全部通过。确认存在：

- `agent_memory`
- `agent_session`
- `agent_event`
- `agent_checkpoint`
- `agent_task`
- `knowledge_base_member`
- `document_element`
- `document_table`
- `document_table_row`
- `document_asset`

确认新增字段：

- `app_user.tenant_id / org_id`
- `knowledge_base.tenant_id / org_id / visibility`
- `eval_case.case_type / evidence`

## 4. 文档与表格验收

| 样例 | 结果 |
|---|---|
| 中文 PDF | Docling 入库成功，3 个元素、3 个 chunk，未触发 PDFBox 降级 |
| HTML 企业制度 | 7 个元素、1 张表、3 行；嵌套正文和表格完整映射 |
| PNG 图片 | Docling OCR 入库成功，3 个 chunk |
| 双页 PDF 跨页表 | 合并为 1 张表、7 行，页码范围 `1-2` |

跨页表第一页 4 行、第二页 3 行，重复表头被合并；行 `0-3` 页码为 1，行 `4-6` 页码为 2。查询“四线城市”命中 row 4，并恢复：

- 表标题
- 表头 `城市等级 | 住宿上限 | 餐补标准`
- 页码范围 `1-2`
- 相邻行和命中行标记

## 5. 多模态验收

`POST /api/search/multimodal` 使用真实 PNG 图片执行：

1. 云端 Vision provider 返回 401，因为当前 `DASHSCOPE_API_KEY` 为空。
2. 系统自动切换 Docling OCR。
3. OCR 描述包含“化学键”。
4. 混合检索 Top1 命中已入库图片文档 `424`。

因此本地多模态入库与 OCR 检索链路已验证。云端 Qwen3-VL 需要补充有效 Key 后再执行真实语义描述验收。

## 6. GraphRAG 验收

- 图谱抽取写入星河科技、制度、采购、休假、化学键和裂解关系。
- 局部图路径作为第三路候选进入 RRF，查询“鍵裂解”返回可解释路径及 `texts-1` 证据。
- 图候选限制为 4 条，避免大量路径挤占文本与表格结果。
- GDS 投影按 KB 隔离，并显式转换为无向关系后执行 Leiden。
- KB `9` 刷新生成 6 个社区摘要；旧社区关系在重建前清理。

## 7. 多 Agent 验收

真实研究任务：

```text
高级工程师年假有多少天？请引用表格证据。
```

运行 `runId=29` 返回 `COMPLETED`，引用 8 条证据。任务图包含：

- `retriever`：1 个工具调用，耗时 428ms
- `analyst-1`：1276 tokens
- `analyst-2`：1907 tokens
- `reviewer`：1736 tokens

两个 Analyst 并行完成，Reviewer 依赖两个成功任务并输出最终答案。所有任务均记录 Token、工具和时限预算。

## 8. 权限验收

验收账号 A 创建知识库 `9` 并授予验收账号 B `VIEWER`：

- 授权前，账号 B 的知识库列表不包含 KB `9`。
- 授权后，账号 B 可见，`accessRole=VIEWER`。
- 账号 B 尝试上传文档返回 404“知识库不存在”，写权限被拒绝。

## 9. Gold Set 门禁

导入 `RAG/TABLE/MULTIMODAL/GRAPH/AGENT` 各 1 条用例：

- `totalCases=5`
- 五类计数均为 1
- `missingCategories=[]`
- `violations=[]`
- 序列化响应 `valid=true`

## 10. 验收中修复

- 自定义 PostgreSQL 镜像切换到带 zhparser 的镜像，恢复中文索引写入。
- Docling OCR 中文语言从 `zh-Hans` 调整为 RapidOCR 支持的 `ch`。
- Docling mapper 递归遍历嵌套 `children`，恢复 HTML 正文、表格和图片。
- 表格无页码时输出“无分页信息/页码未知”，不再出现 `null`。
- 图候选限制为 4 条，避免图路径洪泛。
- GDS 使用 KB 子图投影、无向关系转换和 Leiden。
- 社区刷新清理旧 `IN_COMMUNITY` 关系，摘要提示词显式携带实体列表。
- Gold Set `valid` 改为正式序列化字段。
- Vision 失败时降级到 Docling OCR，并保留失败原因日志。

## 11. 验证命令

```powershell
docker compose --profile full up -d
mvn -q -pl bootstrap -am test
npm run build
```

最终结果：

```text
tests=140 failures=0 errors=0 skipped=0
Vite production build: success
```

## 12. 外部边界

- `DASHSCOPE_API_KEY` 当前为空，云端 Qwen3-VL 语义描述未完成真实调用。
- 数据库 RLS/强制租户视图仍属于后续治理阶段；当前验收覆盖应用层 tenant/org/kb ACL 和数据库角色约束。
- Gold Set 当前使用可复现企业夹具，真实生产业务样本扩充仍需业务方提供脱敏数据。
