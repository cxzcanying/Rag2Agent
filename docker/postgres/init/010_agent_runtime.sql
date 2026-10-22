-- 持久 Agent Runtime：会话、不可变事件日志和检查点。

CREATE TABLE IF NOT EXISTS agent_session (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT NOT NULL,
    session_id     VARCHAR(64) NOT NULL,
    status         VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, session_id)
);

CREATE TABLE IF NOT EXISTS agent_event (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    user_id     BIGINT NOT NULL,
    session_id  VARCHAR(64) NOT NULL,
    seq         BIGINT NOT NULL,
    event_type  VARCHAR(64) NOT NULL,
    payload     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, session_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_agent_event_run_seq
    ON agent_event(run_id, seq);

CREATE TABLE IF NOT EXISTS agent_checkpoint (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    session_id  VARCHAR(64) NOT NULL,
    event_seq   BIGINT NOT NULL,
    state       JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_checkpoint_run
    ON agent_checkpoint(run_id, id DESC);
