-- 后台扫描索引：审批超时与异步入库中断任务按 status + updated_at 扫描，避免全表扫。
-- 注意：init 脚本只在数据卷首次初始化时执行，已有数据卷需手动执行或用迁移脚本补建。
CREATE INDEX IF NOT EXISTS idx_agent_run_status_updated
    ON agent_run(status, updated_at);

CREATE INDEX IF NOT EXISTS idx_ingest_task_status_updated
    ON ingest_task(status, updated_at);

CREATE INDEX IF NOT EXISTS idx_tool_call_run_status
    ON tool_call(run_id, status);
