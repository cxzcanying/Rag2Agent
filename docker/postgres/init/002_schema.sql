-- RAG2Agent 第一期核心表结构（2026-08-07 初版）
-- 依赖 001_extensions.sql 创建的 pgvector 扩展
-- 注意：docker compose 只在数据卷首次初始化时执行本目录脚本；
-- 已有数据卷需手动执行或重建卷。

-- 用户（Sa-Token 登录）
CREATE TABLE IF NOT EXISTS app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    nickname      VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 知识库
CREATE TABLE IF NOT EXISTS knowledge_base (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    description  VARCHAR(512),
    owner_user_id BIGINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 文档（storage_path 为 MinIO object key）
CREATE TABLE IF NOT EXISTS document (
    id           BIGSERIAL PRIMARY KEY,
    kb_id        BIGINT NOT NULL REFERENCES knowledge_base(id),
    file_name    VARCHAR(255) NOT NULL,
    file_type    VARCHAR(32) NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    file_size    BIGINT NOT NULL DEFAULT 0,
    version      INT NOT NULL DEFAULT 1,
    status       VARCHAR(32) NOT NULL DEFAULT 'UPLOADED', -- UPLOADED/INDEXING/INDEXED/FAILED
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 文档切块（embedding 为 BGE-M3 dense 向量，维度 1024）
-- version 与 document.version 对应：每次入库生成新版本，检索只认当前版本
CREATE TABLE IF NOT EXISTS document_chunk (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT NOT NULL REFERENCES document(id),
    kb_id           BIGINT NOT NULL,
    chunk_index     INT NOT NULL,
    content         TEXT NOT NULL,
    token_count     INT NOT NULL DEFAULT 0,
    embedding       vector(1024),
    parent_chunk_id BIGINT,
    page_number     INT,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    version         INT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chunk_document ON document_chunk(document_id);
CREATE INDEX IF NOT EXISTS idx_chunk_kb ON document_chunk(kb_id);
CREATE INDEX IF NOT EXISTS idx_chunk_document_version ON document_chunk(document_id, version);

-- 入库任务状态机：PENDING -> PARSING -> SPLITTING -> EMBEDDING -> INDEXED / FAILED
CREATE TABLE IF NOT EXISTS ingest_task (
    id             BIGSERIAL PRIMARY KEY,
    document_id    BIGINT NOT NULL REFERENCES document(id),
    status         VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    current_stage  VARCHAR(32),
    retry_count    INT NOT NULL DEFAULT 0,
    error_message  TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Agent 执行状态机：INIT -> ROUTING -> EXECUTING -> WAITING_APPROVAL -> FINALIZING
--              -> COMPLETED / FAILED / CANCELLED
CREATE TABLE IF NOT EXISTS agent_run (
    id             BIGSERIAL PRIMARY KEY,
    session_id     VARCHAR(64) NOT NULL,
    user_id        BIGINT NOT NULL DEFAULT 0,
    status         VARCHAR(32) NOT NULL DEFAULT 'INIT',
    query          TEXT NOT NULL,
    max_iterations INT NOT NULL DEFAULT 10,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Agent 步骤（每步记录输入输出，用于轨迹审计与评测）
CREATE TABLE IF NOT EXISTS agent_step (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT NOT NULL REFERENCES agent_run(id),
    seq         INT NOT NULL,
    step_type   VARCHAR(32) NOT NULL, -- ROUTING/RETRIEVE/TOOL/LLM/FINALIZE
    status      VARCHAR(32) NOT NULL,
    input       JSONB,
    output      JSONB,
    duration_ms INT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 工具调用（含人工审批状态）
CREATE TABLE IF NOT EXISTS tool_call (
    id             BIGSERIAL PRIMARY KEY,
    run_id         BIGINT NOT NULL REFERENCES agent_run(id),
    step_id        BIGINT REFERENCES agent_step(id),
    tool_name      VARCHAR(64) NOT NULL,
    status         VARCHAR(32) NOT NULL, -- PENDING/WAITING_APPROVAL/APPROVED/REJECTED/SUCCEEDED/FAILED
    input          JSONB,
    output         JSONB,
    error_message  TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 评测用例（金标文档用 BIGINT[] 存 document id 列表）
CREATE TABLE IF NOT EXISTS eval_case (
    id              BIGSERIAL PRIMARY KEY,
    kb_id           BIGINT NOT NULL REFERENCES knowledge_base(id),
    question        TEXT NOT NULL,
    expected_answer TEXT,
    golden_doc_ids  BIGINT[],
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
