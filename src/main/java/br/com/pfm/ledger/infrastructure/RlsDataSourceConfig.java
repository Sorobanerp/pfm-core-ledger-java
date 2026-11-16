package br.com.pfm.ledger.infrastructure;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Infrastructure configuration wiring PostgreSQL RLS JDBC Interceptor, TransactionManager,
 * Virtual Threads, and Resilience4j / OpenTelemetry readiness.
 */
@Configuration
public class RlsDataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        HikariDataSource hikariDataSource = properties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        return RlsTenantJdbcInterceptor.wrapDataSource(hikariDataSource);
    }

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return RlsTenantJdbcInterceptor.createRlsTransactionManager(dataSource);
    }
}
