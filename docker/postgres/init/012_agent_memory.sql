CREATE TABLE IF NOT EXISTS agent_memory (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    kb_id       BIGINT REFERENCES knowledge_base(id) ON DELETE CASCADE,
    scope       VARCHAR(32) NOT NULL,
    memory_type VARCHAR(32) NOT NULL,
    content     TEXT NOT NULL,
    source      TEXT NOT NULL,
    confidence  DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    embedding   vector(1024) NOT NULL,
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_memory_owner
    ON agent_memory(user_id, kb_id, scope, expires_at);
CREATE INDEX IF NOT EXISTS idx_agent_memory_embedding
    ON agent_memory USING hnsw (embedding vector_cosine_ops);
