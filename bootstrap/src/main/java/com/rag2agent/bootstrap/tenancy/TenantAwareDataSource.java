package com.rag2agent.bootstrap.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** 每次从连接池借出连接时设置 RLS 会话变量，避免池化连接残留上一请求的租户。 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return configure(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return configure(super.getConnection(username, password));
    }

    private Connection configure(Connection connection) throws SQLException {
        Long tenantId = TenantContext.getTenantId();
        String tenant = tenantId == null || tenantId <= 0 ? "0" : tenantId.toString();
        String bypass = TenantContext.isSystem() ? "on" : "off";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT set_config('app.tenant_id', ?, false),
                       set_config('app.system_access', ?, false)
                """)) {
            statement.setString(1, tenant);
            statement.setString(2, bypass);
            statement.execute();
            return connection;
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
    }
}
