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
