-- 安全审计：记录授权变更和拒绝访问，不保存提示词、文件正文或密钥。
CREATE TABLE IF NOT EXISTS security_audit (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT,
    actor_user_id  BIGINT,
    action         VARCHAR(64) NOT NULL,
    resource_type  VARCHAR(64) NOT NULL,
    resource_id    BIGINT,
    outcome        VARCHAR(16) NOT NULL,
    reason         VARCHAR(256),
    details        JSONB NOT NULL DEFAULT '{}'::jsonb,
    trace_id       VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_security_audit_outcome
        CHECK (outcome IN ('ALLOW', 'DENY'))
);

CREATE INDEX IF NOT EXISTS idx_security_audit_tenant_time
    ON security_audit(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_security_audit_actor_time
    ON security_audit(actor_user_id, created_at DESC);

-- 核心业务表补齐租户维度，并从知识库回填存量数据。
ALTER TABLE document ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
UPDATE document d
SET tenant_id = kb.tenant_id
FROM knowledge_base kb
WHERE kb.id = d.kb_id
  AND d.tenant_id IS NULL;
UPDATE document SET tenant_id = 1 WHERE tenant_id IS NULL;
ALTER TABLE document ALTER COLUMN tenant_id SET DEFAULT 1;
ALTER TABLE document ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE document_chunk ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
UPDATE document_chunk c
SET tenant_id = d.tenant_id
FROM document d
WHERE d.id = c.document_id
  AND c.tenant_id IS NULL;
UPDATE document_chunk SET tenant_id = 1 WHERE tenant_id IS NULL;
ALTER TABLE document_chunk ALTER COLUMN tenant_id SET DEFAULT 1;
ALTER TABLE document_chunk ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE ingest_task ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
UPDATE ingest_task task
SET tenant_id = d.tenant_id
FROM document d
WHERE d.id = task.document_id
  AND task.tenant_id IS NULL;
UPDATE ingest_task SET tenant_id = 1 WHERE tenant_id IS NULL;
ALTER TABLE ingest_task ALTER COLUMN tenant_id SET DEFAULT 1;
ALTER TABLE ingest_task ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE document_element ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE document_table ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE document_table_row ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE document_asset ADD COLUMN IF NOT EXISTS tenant_id BIGINT;

UPDATE document_element child
SET tenant_id = d.tenant_id
FROM document d
WHERE d.id = child.document_id AND child.tenant_id IS NULL;
UPDATE document_table child
SET tenant_id = d.tenant_id
FROM document d
WHERE d.id = child.document_id AND child.tenant_id IS NULL;
UPDATE document_table_row child
SET tenant_id = d.tenant_id
FROM document d
WHERE d.id = child.document_id AND child.tenant_id IS NULL;
UPDATE document_asset child
SET tenant_id = d.tenant_id
FROM document d
WHERE d.id = child.document_id AND child.tenant_id IS NULL;

ALTER TABLE document_element ALTER COLUMN tenant_id SET DEFAULT 1;
ALTER TABLE document_table ALTER COLUMN tenant_id SET DEFAULT 1;
ALTER TABLE document_table_row ALTER COLUMN tenant_id SET DEFAULT 1;
ALTER TABLE document_asset ALTER COLUMN tenant_id SET DEFAULT 1;
ALTER TABLE document_element ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE document_table ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE document_table_row ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE document_asset ALTER COLUMN tenant_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_document_tenant
    ON document(tenant_id, kb_id, id);
CREATE INDEX IF NOT EXISTS idx_chunk_tenant
    ON document_chunk(tenant_id, kb_id, document_id);
CREATE INDEX IF NOT EXISTS idx_ingest_task_tenant
    ON ingest_task(tenant_id, document_id, id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_document_id_tenant'
    ) THEN
        ALTER TABLE document ADD CONSTRAINT uk_document_id_tenant UNIQUE (id, tenant_id);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_document_kb_tenant'
    ) THEN
        ALTER TABLE document
            ADD CONSTRAINT fk_document_kb_tenant
            FOREIGN KEY (kb_id, tenant_id)
            REFERENCES knowledge_base(id, tenant_id);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_chunk_document_tenant'
    ) THEN
        ALTER TABLE document_chunk
            ADD CONSTRAINT fk_chunk_document_tenant
            FOREIGN KEY (document_id, tenant_id)
            REFERENCES document(id, tenant_id);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_ingest_task_document_tenant'
    ) THEN
        ALTER TABLE ingest_task
            ADD CONSTRAINT fk_ingest_task_document_tenant
            FOREIGN KEY (document_id, tenant_id)
            REFERENCES document(id, tenant_id);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_document_element_tenant'
    ) THEN
        ALTER TABLE document_element
            ADD CONSTRAINT fk_document_element_tenant
            FOREIGN KEY (document_id, tenant_id)
            REFERENCES document(id, tenant_id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_document_table_tenant'
    ) THEN
        ALTER TABLE document_table
            ADD CONSTRAINT fk_document_table_tenant
            FOREIGN KEY (document_id, tenant_id)
            REFERENCES document(id, tenant_id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_document_table_row_tenant'
    ) THEN
        ALTER TABLE document_table_row
            ADD CONSTRAINT fk_document_table_row_tenant
            FOREIGN KEY (document_id, tenant_id)
            REFERENCES document(id, tenant_id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_document_asset_tenant'
    ) THEN
        ALTER TABLE document_asset
            ADD CONSTRAINT fk_document_asset_tenant
            FOREIGN KEY (document_id, tenant_id)
            REFERENCES document(id, tenant_id) ON DELETE CASCADE;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION app_current_tenant_id() RETURNS BIGINT
LANGUAGE sql STABLE AS $$
    SELECT COALESCE(NULLIF(current_setting('app.tenant_id', true), ''), '0')::BIGINT
$$;

CREATE OR REPLACE FUNCTION app_system_access() RETURNS BOOLEAN
LANGUAGE sql STABLE AS $$
    SELECT COALESCE(current_setting('app.system_access', true), 'off') = 'on'
$$;

-- 超级用户即使 FORCE RLS 也会绕过策略。bootstrap 用户负责迁移，应用固定使用非超级用户角色。
\getenv app_password POSTGRES_PASSWORD
SELECT format('CREATE ROLE rag2agent_app LOGIN PASSWORD %L', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rag2agent_app')
\gexec

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO rag2agent_app', current_database());
END $$;
GRANT USAGE ON SCHEMA public TO rag2agent_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO rag2agent_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO rag2agent_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO rag2agent_app;
DO $$
BEGIN
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
        || 'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rag2agent_app',
        current_user);
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
        || 'GRANT USAGE, SELECT ON SEQUENCES TO rag2agent_app',
        current_user);
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
        || 'GRANT EXECUTE ON FUNCTIONS TO rag2agent_app',
        current_user);
END $$;

ALTER TABLE knowledge_base ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_base FORCE ROW LEVEL SECURITY;
ALTER TABLE knowledge_base_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_base_member FORCE ROW LEVEL SECURITY;
ALTER TABLE document ENABLE ROW LEVEL SECURITY;
ALTER TABLE document FORCE ROW LEVEL SECURITY;
ALTER TABLE document_chunk ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_chunk FORCE ROW LEVEL SECURITY;
ALTER TABLE ingest_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingest_task FORCE ROW LEVEL SECURITY;
ALTER TABLE document_element ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_element FORCE ROW LEVEL SECURITY;
ALTER TABLE document_table ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_table FORCE ROW LEVEL SECURITY;
ALTER TABLE document_table_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_table_row FORCE ROW LEVEL SECURITY;
ALTER TABLE document_asset ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_asset FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON knowledge_base;
CREATE POLICY tenant_isolation ON knowledge_base
    USING (app_system_access() OR tenant_id = app_current_tenant_id())
    WITH CHECK (app_system_access() OR tenant_id = app_current_tenant_id());

DROP POLICY IF EXISTS tenant_isolation ON knowledge_base_member;
CREATE POLICY tenant_isolation ON knowledge_base_member
    USING (app_system_access() OR tenant_id = app_current_tenant_id())
    WITH CHECK (app_system_access() OR tenant_id = app_current_tenant_id());

DROP POLICY IF EXISTS tenant_isolation ON document;
CREATE POLICY tenant_isolation ON document
    USING (app_system_access() OR tenant_id = app_current_tenant_id())
    WITH CHECK (app_system_access() OR tenant_id = app_current_tenant_id());

DROP POLICY IF EXISTS tenant_isolation ON document_chunk;
CREATE POLICY tenant_isolation ON document_chunk
    USING (app_system_access() OR tenant_id = app_current_tenant_id())
    WITH CHECK (app_system_access() OR tenant_id = app_current_tenant_id());

DROP POLICY IF EXISTS tenant_isolation ON ingest_task;
CREATE POLICY tenant_isolation ON ingest_task
    USING (app_system_access() OR tenant_id = app_current_tenant_id())
    WITH CHECK (app_system_access() OR tenant_id = app_current_tenant_id());

DROP POLICY IF EXISTS tenant_isolation ON document_element;
CREATE POLICY tenant_isolation ON document_element
    USING (app_system_access() OR tenant_id = app_current_tenant_id())
    WITH CHECK (app_system_access() OR tenant_id = app_current_tenant_id());

DROP POLICY IF EXISTS tenant_isolation ON document_table;
CREATE POLICY tenant_isolation ON document_table
    USING (app_system_access() OR tenant_id = app_current_tenant_id())
    WITH CHECK (app_system_access() OR tenant_id = app_current_tenant_id());

DROP POLICY IF EXISTS tenant_isolation ON document_table_row;
CREATE POLICY tenant_isolation ON document_table_row
    USING (app_system_access() OR tenant_id = app_current_tenant_id())
    WITH CHECK (app_system_access() OR tenant_id = app_current_tenant_id());

DROP POLICY IF EXISTS tenant_isolation ON document_asset;
CREATE POLICY tenant_isolation ON document_asset
    USING (app_system_access() OR tenant_id = app_current_tenant_id())
    WITH CHECK (app_system_access() OR tenant_id = app_current_tenant_id());
