CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    scope VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_user_id, scope, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_idempotency_created_at ON idempotency_record(created_at);
