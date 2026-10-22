ALTER TABLE tool_call
    ADD COLUMN IF NOT EXISTS approval_digest VARCHAR(64);
