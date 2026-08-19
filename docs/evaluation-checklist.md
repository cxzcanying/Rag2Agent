# 评测清单（D13 待评测项汇总）

> 汇总散落在选型/翻车/TODO 文档中的"需要评测验证"的决策点，供 D13 写评测集时直接对照。
> 原则：没有评测不谈优化。每个待评测项标注当前基线、候选方案、建议指标与出处。

## 检索质量类

### 1. 切块参数（chunkSize / overlap）
- 基线：默认 800 / 100（RecursiveTextSplitter）
- 候选：400/50、800/100、1200/150 等组合
- 指标：Hit@k、MRR
- 出处：tech-selection 第 6 节"切块参数默认值：通过评测实验确定"

### 2. RRF k 值与各阶段 TopK
- 基线：k=60，两路各取 topK
- 背景：k=60 是 RRF 论文推荐的默认值（2009 年 Cormack 等人），作用是平滑排名权重——k 大更看重"跨路共识"（多路都出现），k 小更看重"单路名次"；对中文 chunk + 两路场景未必最优，需实测
- 候选：k ∈ {20, 40, 60, 80, 100} 扫描；各路召回数量（如 20/50/100）
- 指标：Hit@k、MRR
- 出处：tech-selection 第 6 节 + 3.3 节"RRF k 值经评测确定"

### 3. 中文关键词路（pg_trgm vs 中文分词）
- 基线：pg_trgm similarity + ILIKE（中文效果弱，仅精确字面有效）
- 候选：zhparser / pg_jieba 分词 + tsvector/ts_rank；备选 OpenSearch
- 指标：关键词路单独 Hit@k/MRR，与基线 A/B 对比
- 出处：tech-selection 第 6/4 节 + 翻车文档坑 3 + todo.md

### 4. Rerank 分数阈值
- 基线：无阈值，低分（如 0.0045）也会被返回
- 候选：0.3 / 0.5 等阈值，观察召回率与精确率的取舍
- 指标：Hit@k、Top-k 精确率
- 出处：翻车文档坑 3

### 5. 查询增强：HyDE / Query Rewriting
- 基线：疑问句直接向量化检索
- 候选：HyDE（DeepSeek 生成假设答案后向量化）vs Query Rewriting（改写为关键词句）
- 指标：Hit@k、MRR + 额外 LLM 调用成本
- 风险：假设答案幻觉可能带偏检索方向
- 出处：todo.md

### 6. 路由策略：规则版 vs 小模型分类版
- 基线：规则版 QueryRouter（疑问词/短词/混合）
- 候选：deepseek-v4-flash 小模型分类路由
- 指标：路由准确率、端到端 Hit@k、延迟
- 出处：tech-selection 第 7 节"Query Routing（规则 + 小模型分类）"

### 7. PDF 文本规范化（行级换行）
- 基线：PDFBox 提取为逐行 \n，段落优先切块效果打折
- 候选：合并单换行、保留双换行段落后再切块
- 指标：规范化前后切块后检索 Hit@k 对比
- 出处：翻车文档第 3 节"已知优化点"

### 8. 图检索（GraphRAG / Neo4j）是否引入
- 基线：未启用图路，仅向量 + 关键词两路
- 候选：实体抽取建图后加入图召回（RrfFusion 已支持多路）
- 指标：多跳/关系型问题评测集的 Hit@k
- 出处：tech-selection 3.3 节"图（Neo4j，可选）" + 第 7 节

## 生成质量类（对话接口上线后）

### 9. 生成质量：Faithfulness / Answer Correctness
- 基线：无生成接口（待 D11-D12）
- 候选：LLM-as-judge 评估忠实度与答案正确性
- 指标：Faithfulness、Answer Correctness
- 出处：tech-selection 全景决策表"评测"行（Hit@k/MRR/Faithfulness）

## 评测集构建要求

- 从真实知识库抽样构建 100+ 条（question + expected_answer + golden_doc_ids），对应 eval_case 表；
- 覆盖各评测项场景：精确词（编号/API 名）、疑问句语义、关系型多跳（图检索项）；
- 配置矩阵实验：切块 × 检索策略 × 重排开关，结果入库可回溯；
- CI 跑核心评测子集做冒烟，防止改动劣化。

## D13 运行方式

1. 启动 PostgreSQL、Redis、MinIO 和 RocketMQ，登录应用并准备一个知识库。
2. 对已有数据卷执行 `docker/postgres/init/003_evaluation.sql`，新建数据卷会自动执行。
3. 调用 `POST /api/evaluations/cases/import` 导入用例：

```json
{
  "kbId": 1,
  "cases": [
    {
      "question": "如何申请年假？",
      "expectedAnswer": "按制度提交年假申请。",
      "goldenDocumentIds": [12]
    }
  ]
}
```

4. 调用 `POST /api/evaluations/runs` 运行单个配置，或调用 `/api/evaluations/matrix` 批量比较 `strategy/topK/candidateTopK/rrfK/rerankEnabled`。
5. 评测结果写入 `eval_run` 和 `eval_case_result`，页面“评测”页可查看历史聚合指标。生成与裁判默认关闭，开启 `evaluateGeneration` 才会消耗模型额度。

## D13 本次执行结果（2026-08-18）

- 执行状态：**8 条人工核验 smoke 用例、3 组配置均执行完成**。知识库 ID 为 `3`，金标文档为 Java 讲义 `7` 和 MySQL 讲义 `9`，`topK=5`、`candidateTopK=20`、`rrfK=60`，未启用生成质量评测。

| Run ID | 策略 | Rerank | 命中数 | Hit@5 | MRR | 状态 |
|---|---|---:|---:|---:|---:|---|
| 2 | AUTO | 开 | 7/8 | 0.875 | 0.875 | COMPLETED |
| 3 | KEYWORD | 关 | 1/8 | 0.125 | 0.125 | COMPLETED |
| 4 | VECTOR | 开 | 8/8 | 1.000 | 1.000 | COMPLETED |

- Faithfulness / Answer Correctness：本次 `evaluateGeneration=false`，未执行。
- 主要结论：当前小样本中向量检索效果最好；中文 `pg_trgm` 关键词路只命中 1 条，明显是短板；AUTO 因“Java之父是谁？”未返回文档而少命中 1 条，需要继续检查短问句路由及关键词兜底。
- 执行期间修复了 `eval_run` 创建时 PostgreSQL 返回多个 generated keys 导致矩阵启动失败的问题；修复后核心单测 **11/11** 通过，三组运行结果已写入 `eval_run` / `eval_case_result`。
- 限制：这 8 条用例由现有两份文档构造，只能视为 smoke 基线，**不能替代计划要求的 50-100 条真实业务金标集**。

## D13 公开数据集评测结果（2026-08-19）

本轮使用公开、可复现的数据集补充 V1 评测，检索与生成质量分开统计：

- **MIRACL zh dev**：Apache-2.0。dataset revision=`5be20db9509754dadad47689368639fcec739c00`，corpus revision=`d921ec7e349ce0d28daf30b2da9da5ee698bef0d`；本地下载中文 corpus 第 0 分片，SHA256=`b036ba6a927a4598b314cae55a1de200d2a619b28b4327f49e97ec4233d324827`。构造 100 条检索用例、339 篇文档，仅用于 Hit@5/MRR，不代表完整 MIRACL 中文语料。
- **PaddlePaddle/dureader_robust**：Apache-2.0。revision=`10003b9e4d5e0afaff73fcf366f02d49880ec17a`，原始包 SHA256=`99bed9ced8995df1c89b9789f890c27a13b4650a56b4d973907cc28da8bd9f0f`。构造 30 条带参考答案用例、60 篇文档，用于生成质量评测。

### MIRACL zh 检索配置对比

知识库 `kbId=4`，`topK=5`、`candidateTopK=20`、`rrfK=60`。

| Run ID | 策略 | Rerank | 用例数 | Hit@5 | MRR | 状态 |
|---|---|---:|---:|---:|---:|---|
| 5 | VECTOR | 开 | 100 | 1.000 | 0.955 | COMPLETED |
| 6 | KEYWORD | 关 | 100 | 0.010 | 0.010 | COMPLETED |
| 7 | AUTO | 开 | 100 | 0.660 | 0.630 | COMPLETED |

结论：当前公开子集上 VECTOR 明显优于 `pg_trgm` 中文关键词路；AUTO 受规则路由影响，部分短问句未走向量路。该结果支持 TODO 中引入中文分词或独立搜索引擎的方向，但不应外推为完整 MIRACL 的最终结论。

### DuReader Robust 生成质量

知识库 `kbId=5`，Run ID=`8`，30/30 条结果持久化，0 errors，状态 `COMPLETED`。

| 指标 | 结果 |
|---|---:|
| Hit@5 | 1.000 |
| MRR | 1.000 |
| Faithfulness | 0.930 |
| Answer Correctness | 0.8433 |

说明：每题包含一次回答模型调用和一次裁判模型调用，生成评测耗时超过客户端默认 120 秒；客户端曾出现 `requests.ReadTimeout`，但后端任务未中断并最终完成，详见翻车文档 D13 条目。后续应改为异步任务或提供按 `runId` 查询状态的接口。

### 可复现入口

- `scripts/evaluation/prepare_miracl_zh.py`：下载固定 revision 并生成 MIRACL 中文 PDF/用例。
- `scripts/evaluation/prepare_dureader_robust.py`：下载固定 revision 并生成 DuReader PDF/用例。
- `scripts/evaluation/run_miracl_eval.py`：批量上传、断点续跑、触发并汇总评测运行。

原始数据、生成 PDF、运行状态和凭证均位于 `eval-data/`，已加入 `.gitignore`，不进入 Git 提交。
