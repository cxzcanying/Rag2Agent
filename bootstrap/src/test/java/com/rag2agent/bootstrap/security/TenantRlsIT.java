package com.rag2agent.bootstrap.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;

/** 需先在目标数据库执行 016_security_hardening.sql；IT 后缀避免常规测试误连中间件。 */
class TenantRlsIT {

    private static final String URL = System.getenv().getOrDefault(
            "RAG2AGENT_TEST_DB_URL", "jdbc:postgresql://localhost:15432/rag2agent");
    private static final String USER = System.getenv().getOrDefault("POSTGRES_APP_USER", "rag2agent_app");
    private static final String PASSWORD = System.getenv().getOrDefault("POSTGRES_APP_PASSWORD", "rag2agent");

    @Test
    void rowLevelSecurityBlocksCrossTenantKnowledgeBases() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            connection.setAutoCommit(false);
            try {
                setContext(connection, "on", "0");
                execute(connection, """
                        INSERT INTO tenant (id, code, name) VALUES (2, 'rls-test', 'RLS Test')
                        ON CONFLICT (id) DO NOTHING
                        """);
                execute(connection, """
                        INSERT INTO organization (id, tenant_id, code, name)
                        VALUES (900002, 2, 'rls-test', 'RLS Test')
                        ON CONFLICT (id) DO NOTHING
                        """);
                execute(connection, """
                        INSERT INTO knowledge_base
                            (id, name, owner_user_id, tenant_id, org_id, visibility)
                        VALUES
                            (900001, 'tenant-one', 0, 1, 1, 'PRIVATE'),
                            (900002, 'tenant-two', 0, 2, 900002, 'PRIVATE')
                        ON CONFLICT (id) DO NOTHING
                        """);

                setContext(connection, "off", "1");
                assertEquals(1, count(connection, "SELECT count(*) FROM knowledge_base WHERE id IN (900001, 900002)"));
                assertTrue(exists(connection, 900001));
                assertFalse(exists(connection, 900002));

                setContext(connection, "off", "2");
                assertFalse(exists(connection, 900001));
                assertTrue(exists(connection, 900002));
            } finally {
                connection.rollback();
            }
        }
    }

    private void setContext(Connection connection, String systemAccess, String tenantId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT set_config('app.system_access', ?, true),
                       set_config('app.tenant_id', ?, true)
                """)) {
            statement.setString(1, systemAccess);
            statement.setString(2, tenantId);
            statement.execute();
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private int count(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private boolean exists(Connection connection, long id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM knowledge_base WHERE id = ?)")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }
}
