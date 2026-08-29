CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'zhparser') THEN
        CREATE EXTENSION IF NOT EXISTS zhparser;
        IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'rag2agent_zhcfg') THEN
            CREATE TEXT SEARCH CONFIGURATION rag2agent_zhcfg (PARSER = zhparser);
            ALTER TEXT SEARCH CONFIGURATION rag2agent_zhcfg
                ADD MAPPING FOR n, v, a, i, e, l WITH simple;
        END IF;
    END IF;
END
$$;
