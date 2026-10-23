-- 每日 AI 预算按北京时间切分；请求开始前预占，完成后按账本实际金额结算。
CREATE TABLE IF NOT EXISTS ai_budget_reservation (
    request_id      VARCHAR(128) PRIMARY KEY,
    billing_date    DATE NOT NULL,
    tenant_id       BIGINT,
    user_id         BIGINT,
    function_name   VARCHAR(64) NOT NULL,
    reserved_cost   NUMERIC(20, 10) NOT NULL,
    actual_cost     NUMERIC(20, 10),
    currency        CHAR(3) NOT NULL DEFAULT 'USD',
    status          VARCHAR(16) NOT NULL DEFAULT 'RESERVED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ai_budget_reservation_date
    ON ai_budget_reservation(billing_date, tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_ai_budget_reservation_created
    ON ai_budget_reservation(status, created_at);
