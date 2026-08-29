DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'zhparser')
       AND EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'rag2agent_zhcfg') THEN
        ALTER TABLE document_chunk
            ADD COLUMN IF NOT EXISTS content_zh_tsv tsvector
            GENERATED ALWAYS AS (to_tsvector('rag2agent_zhcfg'::regconfig, content)) STORED;
        CREATE INDEX IF NOT EXISTS idx_chunk_content_zh_tsv
            ON document_chunk USING GIN (content_zh_tsv);
    END IF;
END
$$;
