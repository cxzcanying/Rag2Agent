-- D13 评测运行与逐题结果。已有数据卷需手动执行本文件，新建数据卷会自动执行。
CREATE TABLE IF NOT EXISTS eval_run (
    id                 BIGSERIAL PRIMARY KEY,
    kb_id              BIGINT NOT NULL REFERENCES knowledge_base(id),
    name               VARCHAR(128) NOT NULL,
    status             VARCHAR(32) NOT NULL,
    config             JSONB NOT NULL,
    total_cases        INT NOT NULL DEFAULT 0,
    hit_at_k           DOUBLE PRECISION,
    mrr                DOUBLE PRECISION,
    faithfulness       DOUBLE PRECISION,
    answer_correctness DOUBLE PRECISION,
    error_message      TEXT,
    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS eval_case_result (
    id                 BIGSERIAL PRIMARY KEY,
    run_id             BIGINT NOT NULL REFERENCES eval_run(id) ON DELETE CASCADE,
    case_id            BIGINT NOT NULL REFERENCES eval_case(id),
    first_relevant_rank INT NOT NULL DEFAULT 0,
    reciprocal_rank    DOUBLE PRECISION NOT NULL DEFAULT 0,
    returned_doc_ids   BIGINT[] NOT NULL DEFAULT '{}',
    generated_answer   TEXT,
    faithfulness       DOUBLE PRECISION,
    answer_correctness DOUBLE PRECISION,
    latency_ms         INT NOT NULL,
    error_message      TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 异步提交使用请求幂等键防止客户端超时重试重复消耗模型额度。
ALTER TABLE eval_run ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);
ALTER TABLE eval_run ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(64);
ALTER TABLE eval_run ADD COLUMN IF NOT EXISTS case_ids BIGINT[] NOT NULL DEFAULT '{}';

CREATE INDEX IF NOT EXISTS idx_eval_case_kb_id ON eval_case(kb_id);
CREATE INDEX IF NOT EXISTS idx_eval_run_kb_id ON eval_run(kb_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_eval_case_result_run_id ON eval_case_result(run_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_eval_run_idempotency
    ON eval_run(kb_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_eval_case_result_run_case
    ON eval_case_result(run_id, case_id);
