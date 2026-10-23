ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS entity_key VARCHAR(256);
ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS predicate VARCHAR(128);
ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS value_json JSONB;
ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS event_at TIMESTAMPTZ;
ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS recorded_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS valid_from TIMESTAMPTZ;
ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS valid_to TIMESTAMPTZ;
ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS supersedes_id BIGINT;
ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS superseded_by BIGINT;
ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS source_type VARCHAR(32) NOT NULL DEFAULT 'USER';
ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS source_run_id BIGINT;
ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS source_message_id VARCHAR(128);
ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS source_hash VARCHAR(64);
ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE agent_memory ADD COLUMN IF NOT EXISTS last_accessed_at TIMESTAMPTZ;

UPDATE agent_memory
SET tenant_id = COALESCE(
        tenant_id,
        (SELECT kb.tenant_id FROM knowledge_base kb WHERE kb.id = agent_memory.kb_id),
        1),
    event_at = COALESCE(event_at, created_at),
    valid_from = COALESCE(valid_from, event_at, created_at),
    source_hash = COALESCE(source_hash, md5(content)),
    content_hash = COALESCE(content_hash, md5(content)),
    status = COALESCE(status, 'ACTIVE')
WHERE tenant_id IS NULL
   OR event_at IS NULL
   OR valid_from IS NULL
   OR source_hash IS NULL
   OR content_hash IS NULL;

ALTER TABLE agent_memory ALTER COLUMN tenant_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_memory_status
    ON agent_memory(user_id, tenant_id, status, kb_id, updated_at DESC);

WITH duplicate AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY user_id, COALESCE(kb_id, 0), content_hash
               ORDER BY updated_at DESC, id DESC
           ) AS position
    FROM agent_memory
    WHERE status = 'ACTIVE'
)
UPDATE agent_memory memory
SET status = 'SUPERSEDED', valid_to = COALESCE(memory.valid_to, now())
FROM duplicate
WHERE duplicate.id = memory.id AND duplicate.position > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_memory_active_hash
    ON agent_memory(user_id, COALESCE(kb_id, 0), content_hash)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS agent_memory_event (
    id          BIGSERIAL PRIMARY KEY,
    memory_id   BIGINT,
    user_id     BIGINT NOT NULL,
    tenant_id   BIGINT NOT NULL,
    action      VARCHAR(16) NOT NULL,
    reason      TEXT,
    source_hash VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_memory_event_user_time
    ON agent_memory_event(user_id, tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_session_summary (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT NOT NULL,
    session_id     VARCHAR(64) NOT NULL,
    source_from_seq BIGINT NOT NULL DEFAULT 0,
    source_to_seq   BIGINT NOT NULL DEFAULT 0,
    version         VARCHAR(32) NOT NULL DEFAULT 'v1',
    summary         TEXT NOT NULL,
    request_id      VARCHAR(128),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_session_summary_session
    ON agent_session_summary(user_id, session_id, created_at DESC);
