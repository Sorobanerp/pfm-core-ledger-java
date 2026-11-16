package br.com.pfm.ledger.infrastructure;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.TransactionDefinition;

/**
 * JDBC & Transaction Interceptor that enforces PostgreSQL Row-Level Security (RLS) on every connection/transaction.
 * Executes {@code SET LOCAL app.current_tenant_id = '<uuid>'} (or resets to empty string when unbound)
 * before any SQL statement executes in the transaction.
 */
public final class RlsTenantJdbcInterceptor {

    private RlsTenantJdbcInterceptor() {
    }

    public static void applyTenantSessionContext(Connection connection) throws SQLException {
        Optional<UUID> tenantIdOpt = TenantContextHolder.getTenantId();
        try (Statement stmt = connection.createStatement()) {
            if (tenantIdOpt.isPresent()) {
                // UUID validation ensures zero SQL injection vector
                String sanitizedUuid = UUID.fromString(tenantIdOpt.get().toString()).toString();
                stmt.execute("SET LOCAL app.current_tenant_id = '" + sanitizedUuid + "'");
            } else {
                stmt.execute("SET LOCAL app.current_tenant_id = ''");
            }
        }
    }

    /**
     * Wraps a {@link DataSource} so every acquired connection automatically
     * configures {@code app.current_tenant_id} from {@link TenantContextHolder}.
     */
    public static DataSource wrapDataSource(DataSource targetDataSource) {
        return new DelegatingDataSource(targetDataSource) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection conn = obtainTargetDataSource().getConnection();
                applyTenantSessionContext(conn);
                return conn;
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                Connection conn = obtainTargetDataSource().getConnection(username, password);
                applyTenantSessionContext(conn);
                return conn;
            }
        };
    }

    /**
     * Creates a {@link DataSourceTransactionManager} that executes {@code SET LOCAL app.current_tenant_id = '<uuid>'}
     * inside {@code prepareTransactionalConnection} at the beginning of every local database transaction.
     */
    public static DataSourceTransactionManager createRlsTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource) {
            @Override
            protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
                    throws SQLException {
                super.prepareTransactionalConnection(con, definition);
                applyTenantSessionContext(con);
            }
        };
    }
}
