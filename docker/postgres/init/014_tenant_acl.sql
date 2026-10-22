-- 企业租户与知识库 ACL。已有数据归入默认租户和组织，后续可迁移到独立租户。

CREATE TABLE IF NOT EXISTS tenant (
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(64) NOT NULL UNIQUE,
    name       VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO tenant (id, code, name)
VALUES (1, 'default', '默认租户')
ON CONFLICT (id) DO NOTHING;
SELECT setval(pg_get_serial_sequence('tenant', 'id'),
              GREATEST((SELECT MAX(id) FROM tenant), 1));

CREATE TABLE IF NOT EXISTS organization (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT NOT NULL REFERENCES tenant(id),
    code       VARCHAR(64) NOT NULL,
    name       VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code),
    UNIQUE (id, tenant_id)
);

INSERT INTO organization (id, tenant_id, code, name)
VALUES (1, 1, 'default', '默认组织')
ON CONFLICT (id) DO NOTHING;
SELECT setval(pg_get_serial_sequence('organization', 'id'),
              GREATEST((SELECT MAX(id) FROM organization), 1));

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS org_id BIGINT;
UPDATE app_user SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE app_user SET org_id = 1 WHERE org_id IS NULL;
ALTER TABLE app_user ALTER COLUMN tenant_id SET DEFAULT 1;
ALTER TABLE app_user ALTER COLUMN org_id SET DEFAULT 1;
ALTER TABLE app_user ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE app_user ALTER COLUMN org_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_app_user_tenant_id'
    ) THEN
        ALTER TABLE app_user
            ADD CONSTRAINT uk_app_user_tenant_id UNIQUE (id, tenant_id);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_app_user_tenant'
    ) THEN
        ALTER TABLE app_user
            ADD CONSTRAINT fk_app_user_tenant
            FOREIGN KEY (tenant_id) REFERENCES tenant(id);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_app_user_organization'
    ) THEN
        ALTER TABLE app_user
            ADD CONSTRAINT fk_app_user_organization
            FOREIGN KEY (org_id, tenant_id)
            REFERENCES organization(id, tenant_id);
    END IF;
END $$;

ALTER TABLE knowledge_base ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE knowledge_base ADD COLUMN IF NOT EXISTS org_id BIGINT;
ALTER TABLE knowledge_base ADD COLUMN IF NOT EXISTS visibility VARCHAR(32);
UPDATE knowledge_base kb
SET tenant_id = COALESCE(owner.tenant_id, 1),
    org_id = COALESCE(owner.org_id, 1),
    visibility = COALESCE(NULLIF(kb.visibility, ''), 'PRIVATE')
FROM app_user owner
WHERE owner.id = kb.owner_user_id
  AND (kb.tenant_id IS NULL OR kb.org_id IS NULL OR kb.visibility IS NULL);
UPDATE knowledge_base
SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE knowledge_base
SET org_id = 1 WHERE org_id IS NULL;
UPDATE knowledge_base
SET visibility = 'PRIVATE' WHERE visibility IS NULL;
ALTER TABLE knowledge_base ALTER COLUMN tenant_id SET DEFAULT 1;
ALTER TABLE knowledge_base ALTER COLUMN org_id SET DEFAULT 1;
ALTER TABLE knowledge_base ALTER COLUMN visibility SET DEFAULT 'PRIVATE';
ALTER TABLE knowledge_base ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE knowledge_base ALTER COLUMN org_id SET NOT NULL;
ALTER TABLE knowledge_base ALTER COLUMN visibility SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_knowledge_base_tenant_id'
    ) THEN
        ALTER TABLE knowledge_base
            ADD CONSTRAINT uk_knowledge_base_tenant_id UNIQUE (id, tenant_id);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_knowledge_base_visibility'
    ) THEN
        ALTER TABLE knowledge_base
            ADD CONSTRAINT ck_knowledge_base_visibility
            CHECK (visibility IN ('PRIVATE', 'ORGANIZATION', 'TENANT'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_knowledge_base_tenant'
    ) THEN
        ALTER TABLE knowledge_base
            ADD CONSTRAINT fk_knowledge_base_tenant
            FOREIGN KEY (tenant_id) REFERENCES tenant(id);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_knowledge_base_organization'
    ) THEN
        ALTER TABLE knowledge_base
            ADD CONSTRAINT fk_knowledge_base_organization
            FOREIGN KEY (org_id, tenant_id)
            REFERENCES organization(id, tenant_id);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS knowledge_base_member (
    kb_id      BIGINT NOT NULL,
    user_id    BIGINT NOT NULL,
    tenant_id  BIGINT NOT NULL,
    role       VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (kb_id, user_id),
    CONSTRAINT fk_knowledge_base_member_kb
        FOREIGN KEY (kb_id, tenant_id)
        REFERENCES knowledge_base(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_base_member_user
        FOREIGN KEY (user_id, tenant_id)
        REFERENCES app_user(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_base_member_role
        CHECK (role IN ('VIEWER', 'EDITOR', 'ADMIN'))
);

CREATE INDEX IF NOT EXISTS idx_kb_member_user
    ON knowledge_base_member(user_id, tenant_id);
CREATE INDEX IF NOT EXISTS idx_kb_tenant_visibility
    ON knowledge_base(tenant_id, visibility, id);
