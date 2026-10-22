-- 多模态文档结构。版本与 document.version 对齐，检索和读取只暴露当前版本。

CREATE TABLE IF NOT EXISTS document_element (
    element_id        VARCHAR(256) NOT NULL,
    document_id       BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    kb_id             BIGINT NOT NULL,
    version           INT NOT NULL,
    parent_element_id VARCHAR(256),
    element_type      VARCHAR(64) NOT NULL,
    ordinal           INT NOT NULL,
    page_from         INT,
    page_to           INT,
    bbox              JSONB,
    text_content      TEXT,
    metadata          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, version, element_id)
);

CREATE INDEX IF NOT EXISTS idx_document_element_kb
    ON document_element(kb_id, document_id, version);
CREATE INDEX IF NOT EXISTS idx_document_element_page
    ON document_element(document_id, version, page_from, ordinal);

CREATE TABLE IF NOT EXISTS document_table (
    table_id       VARCHAR(256) NOT NULL,
    document_id    BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    kb_id          BIGINT NOT NULL,
    version        INT NOT NULL,
    title          TEXT,
    headers        JSONB NOT NULL DEFAULT '[]'::jsonb,
    page_from      INT,
    page_to        INT,
    bbox           JSONB,
    metadata       JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, version, table_id)
);

CREATE INDEX IF NOT EXISTS idx_document_table_kb
    ON document_table(kb_id, document_id, version);

CREATE TABLE IF NOT EXISTS document_table_row (
    row_id         VARCHAR(256) NOT NULL,
    table_id       VARCHAR(256) NOT NULL,
    document_id    BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    kb_id          BIGINT NOT NULL,
    version        INT NOT NULL,
    row_index      INT NOT NULL,
    cells          JSONB NOT NULL DEFAULT '[]'::jsonb,
    semantic_text  TEXT NOT NULL,
    page_no        INT,
    bbox           JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, version, row_id),
    CONSTRAINT fk_document_table_row_table
        FOREIGN KEY (document_id, version, table_id)
        REFERENCES document_table(document_id, version, table_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_document_table_row_table
    ON document_table_row(document_id, version, table_id, row_index);

CREATE TABLE IF NOT EXISTS document_asset (
    asset_id          VARCHAR(256) NOT NULL,
    document_id       BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    kb_id             BIGINT NOT NULL,
    version           INT NOT NULL,
    source_element_id VARCHAR(256),
    asset_type        VARCHAR(64) NOT NULL,
    title             TEXT,
    content_reference TEXT,
    page_no           INT,
    bbox              JSONB,
    metadata          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, version, asset_id)
);

CREATE INDEX IF NOT EXISTS idx_document_asset_kb
    ON document_asset(kb_id, document_id, version);
