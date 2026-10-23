ALTER TABLE document ADD COLUMN IF NOT EXISTS parser_name VARCHAR(32);
ALTER TABLE document ADD COLUMN IF NOT EXISTS quality_json JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE ingest_task ADD COLUMN IF NOT EXISTS quality_json JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS idx_ingest_task_status_updated
    ON ingest_task(status, updated_at);
