ALTER TABLE eval_run ADD COLUMN IF NOT EXISTS repro_manifest JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE eval_run ADD COLUMN IF NOT EXISTS dataset_version VARCHAR(128);
ALTER TABLE eval_run ADD COLUMN IF NOT EXISTS harness_version VARCHAR(128);

ALTER TABLE eval_case_result ADD COLUMN IF NOT EXISTS case_type VARCHAR(32) NOT NULL DEFAULT 'RAG';
ALTER TABLE eval_case_result ADD COLUMN IF NOT EXISTS evidence_hit BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE eval_case_result ADD COLUMN IF NOT EXISTS evidence_result JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE eval_case_result ADD COLUMN IF NOT EXISTS error_class VARCHAR(32);
ALTER TABLE eval_case_result ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);
ALTER TABLE eval_case_result ADD COLUMN IF NOT EXISTS judge_provider VARCHAR(64);
ALTER TABLE eval_case_result ADD COLUMN IF NOT EXISTS judge_model VARCHAR(128);
ALTER TABLE eval_case_result ADD COLUMN IF NOT EXISTS judge_prompt_version VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_eval_result_case_type
    ON eval_case_result(case_type, evidence_hit);
