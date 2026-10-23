package com.rag2agent.bootstrap.tenancy;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantAwareDataSourceTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void configuresTenantAndSystemFlagsForBorrowedConnection() throws Exception {
        DataSource target = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(target.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        TenantAwareDataSource dataSource = new TenantAwareDataSource(target);

        TenantContext.set(7);
        assertSame(connection, dataSource.getConnection());
        verify(statement).setString(1, "7");
        verify(statement).setString(2, "off");

        TenantContext.runAsSystem(() -> {
            try {
                dataSource.getConnection();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        verify(statement).setString(1, "0");
        verify(statement).setString(2, "on");
    }
}
