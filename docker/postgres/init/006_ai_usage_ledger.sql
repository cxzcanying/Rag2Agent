CREATE TABLE IF NOT EXISTS ai_usage_ledger (
    id BIGSERIAL PRIMARY KEY,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    estimated_prompt_tokens BIGINT NOT NULL DEFAULT 0,
    cached_prompt_tokens BIGINT NOT NULL DEFAULT 0,
    prompt_cost NUMERIC(20, 10) NOT NULL DEFAULT 0,
    completion_cost NUMERIC(20, 10) NOT NULL DEFAULT 0,
    total_cost NUMERIC(20, 10) NOT NULL DEFAULT 0,
    currency CHAR(3),
    price_version VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ai_usage_ledger_provider_model_created
    ON ai_usage_ledger(provider, model, created_at);
