# 指标命名与 outcome 约定

业务指标统一使用 `rag2agent.<domain>.<operation>` 命名，标签只保留低基数维度。请求结果统一通过 `outcome` 表达，不把异常消息或用户输入放入标签。

允许的 `outcome` 值：

| 类别 | 值 |
| --- | --- |
| 请求完成 | `success`、`error` |
| 依赖与资源 | `timeout`、`rejected`、`interrupted`、`dependency_error` |
| 限流与安全 | `allowed`、`flagged`、`rejected` |
| 缓存 | `hit`、`miss`、`error`、`write_error` |

核心指标：

| 指标 | 必要标签 |
| --- | --- |
| `rag2agent.ai.requests` | `provider`、`model`、`operation`、`outcome` |
| `rag2agent.search.duration` | `strategy`、`outcome` |
| `rag2agent.agent.tool.duration` | `tool`、`outcome` |
| `rag2agent.executor.queue.depth` | `executor` |
| `rag2agent.executor.queue.capacity` | `executor` |
| `rag2agent.lock.operations` | `lock`、`operation`、`outcome` |

新增指标或标签时保持小写、稳定枚举和低基数；详细标签说明应同步更新本文档。
