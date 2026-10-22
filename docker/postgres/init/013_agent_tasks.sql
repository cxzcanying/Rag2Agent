CREATE TABLE IF NOT EXISTS agent_task (
    id             BIGSERIAL PRIMARY KEY,
    run_id         BIGINT NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    parent_task_id BIGINT REFERENCES agent_task(id) ON DELETE CASCADE,
    assignee       VARCHAR(64),
    task_type      VARCHAR(64) NOT NULL,
    title          VARCHAR(256) NOT NULL,
    instruction    TEXT NOT NULL,
    status         VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    depends_on     BIGINT[] NOT NULL DEFAULT '{}',
    output         JSONB,
    error_message  TEXT,
    started_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_task_run_status
    ON agent_task(run_id, status, id);
