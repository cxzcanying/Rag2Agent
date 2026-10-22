-- 发布门禁 Gold Set：在通用检索用例上增加场景类型和结构化证据契约。

ALTER TABLE eval_case ADD COLUMN IF NOT EXISTS case_type VARCHAR(32);
ALTER TABLE eval_case ADD COLUMN IF NOT EXISTS evidence JSONB;
UPDATE eval_case SET case_type = 'RAG' WHERE case_type IS NULL;
UPDATE eval_case SET evidence = '{}'::jsonb WHERE evidence IS NULL;
ALTER TABLE eval_case ALTER COLUMN case_type SET DEFAULT 'RAG';
ALTER TABLE eval_case ALTER COLUMN evidence SET DEFAULT '{}'::jsonb;
ALTER TABLE eval_case ALTER COLUMN case_type SET NOT NULL;
ALTER TABLE eval_case ALTER COLUMN evidence SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_eval_case_type'
    ) THEN
        ALTER TABLE eval_case
            ADD CONSTRAINT ck_eval_case_type
            CHECK (case_type IN ('RAG', 'TABLE', 'MULTIMODAL', 'GRAPH', 'AGENT'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_eval_case_kb_type
    ON eval_case(kb_id, case_type, id);
