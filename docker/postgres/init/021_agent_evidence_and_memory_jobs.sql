-- Agent 运行保存知识库边界，供评测回放和记忆抽取复用。
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS kb_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_agent_run_kb_id
    ON agent_run(kb_id, created_at DESC);

-- 自动记忆抽取使用持久任务表，应用重启后仍可审计处理状态。
CREATE TABLE IF NOT EXISTS agent_memory_job (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL,
    tenant_id       BIGINT NOT NULL,
    kb_id           BIGINT NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    extracted_count INT NOT NULL DEFAULT 0,
    error_message   TEXT,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_memory_job_status
    ON agent_memory_job(status, created_at);

ALTER TABLE agent_session_summary ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
UPDATE agent_session_summary summary
SET tenant_id = COALESCE(
        summary.tenant_id,
        (SELECT app_user.tenant_id FROM app_user WHERE app_user.id = summary.user_id),
        1)
WHERE summary.tenant_id IS NULL;
ALTER TABLE agent_session_summary ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE agent_memory ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_memory FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_memory_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_memory_event FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_memory_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_memory_job FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_session_summary ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_session_summary FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON agent_memory;
CREATE POLICY tenant_isolation ON agent_memory
    USING (app_system_access() OR tenant_id = app_current_tenant_id())
    WITH CHECK (app_system_access() OR tenant_id = app_current_tenant_id());

DROP POLICY IF EXISTS tenant_isolation ON agent_memory_event;
CREATE POLICY tenant_isolation ON agent_memory_event
    USING (app_system_access() OR tenant_id = app_current_tenant_id())
    WITH CHECK (app_system_access() OR tenant_id = app_current_tenant_id());

DROP POLICY IF EXISTS tenant_isolation ON agent_memory_job;
CREATE POLICY tenant_isolation ON agent_memory_job
    USING (app_system_access() OR tenant_id = app_current_tenant_id())
    WITH CHECK (app_system_access() OR tenant_id = app_current_tenant_id());

DROP POLICY IF EXISTS tenant_isolation ON agent_session_summary;
CREATE POLICY tenant_isolation ON agent_session_summary
    USING (app_system_access() OR tenant_id = app_current_tenant_id())
    WITH CHECK (app_system_access() OR tenant_id = app_current_tenant_id());
