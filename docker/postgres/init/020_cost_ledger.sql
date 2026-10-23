-- 每次模型请求独立记账。时间归属使用 started_at/provider_created_at，不使用完成时间。
ALTER TABLE ai_usage_ledger ADD COLUMN IF NOT EXISTS request_id VARCHAR(128);
ALTER TABLE ai_usage_ledger ADD COLUMN IF NOT EXISTS task_id BIGINT;
ALTER TABLE ai_usage_ledger ADD COLUMN IF NOT EXISTS run_id BIGINT;
ALTER TABLE ai_usage_ledger ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE ai_usage_ledger ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE ai_usage_ledger ADD COLUMN IF NOT EXISTS function_name VARCHAR(64) NOT NULL DEFAULT 'unknown';
ALTER TABLE ai_usage_ledger ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ;
ALTER TABLE ai_usage_ledger ADD COLUMN IF NOT EXISTS provider_created_at TIMESTAMPTZ;
ALTER TABLE ai_usage_ledger ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;
ALTER TABLE ai_usage_ledger ADD COLUMN IF NOT EXISTS cache_hit_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_usage_ledger ADD COLUMN IF NOT EXISTS cache_miss_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_usage_ledger ADD COLUMN IF NOT EXISTS input_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_usage_ledger ADD COLUMN IF NOT EXISTS output_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_usage_ledger ADD COLUMN IF NOT EXISTS reasoning_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_usage_ledger ADD COLUMN IF NOT EXISTS billing_tier VARCHAR(16) NOT NULL DEFAULT 'unknown';
ALTER TABLE ai_usage_ledger ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';

UPDATE ai_usage_ledger
SET started_at = COALESCE(started_at, created_at),
    completed_at = COALESCE(completed_at, created_at),
    input_tokens = CASE WHEN input_tokens = 0 THEN prompt_tokens ELSE input_tokens END,
    output_tokens = CASE WHEN output_tokens = 0 THEN completion_tokens ELSE output_tokens END,
    cache_hit_tokens = CASE
        WHEN cache_hit_tokens = 0 THEN cached_prompt_tokens ELSE cache_hit_tokens END,
    cache_miss_tokens = CASE
        WHEN cache_miss_tokens = 0 THEN GREATEST(prompt_tokens - cached_prompt_tokens, 0)
        ELSE cache_miss_tokens END
WHERE started_at IS NULL
   OR completed_at IS NULL
   OR input_tokens = 0
   OR output_tokens = 0
   OR cache_hit_tokens = 0
   OR cache_miss_tokens = 0;

CREATE INDEX IF NOT EXISTS idx_ai_usage_ledger_tenant_time
    ON ai_usage_ledger(tenant_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_ai_usage_ledger_user_time
    ON ai_usage_ledger(user_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_ai_usage_ledger_run
    ON ai_usage_ledger(run_id, started_at);
CREATE INDEX IF NOT EXISTS idx_ai_usage_ledger_request
    ON ai_usage_ledger(request_id);

CREATE TABLE IF NOT EXISTS ai_provider_balance (
    id          BIGSERIAL PRIMARY KEY,
    provider    VARCHAR(64) NOT NULL,
    currency    CHAR(3) NOT NULL,
    balance     NUMERIC(20, 10) NOT NULL,
    source      VARCHAR(64) NOT NULL DEFAULT 'manual',
    checked_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_provider_balance_provider_time
    ON ai_provider_balance(provider, checked_at DESC);
