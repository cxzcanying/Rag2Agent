ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS client_request_id VARCHAR(128);
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS answer TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_run_client_request
    ON agent_run(user_id, client_request_id) WHERE client_request_id IS NOT NULL;
