package br.com.pfm.ledger.adapter.out.postgres;

import br.com.pfm.ledger.application.port.out.AuditLogRepositoryPort;
import br.com.pfm.ledger.domain.AuditLogEntry;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL RLS Outbound Adapter for Immutable Audit Log (ADR-010).
 * Protected by Resilience4j CircuitBreaker and Bulkhead.
 */
@Repository
public class PostgresRlsAuditLogAdapter implements AuditLogRepositoryPort {

    private final JdbcTemplate jdbcTemplate;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    public PostgresRlsAuditLogAdapter(
            JdbcTemplate jdbcTemplate,
            CircuitBreaker coreLedgerCircuitBreaker,
            Bulkhead coreLedgerBulkhead
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.circuitBreaker = Objects.requireNonNull(coreLedgerCircuitBreaker, "coreLedgerCircuitBreaker must not be null");
        this.bulkhead = Objects.requireNonNull(coreLedgerBulkhead, "coreLedgerBulkhead must not be null");
    }

    @Override
    @Transactional
    public void append(AuditLogEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        Runnable task = Bulkhead.decorateRunnable(
                bulkhead,
                CircuitBreaker.decorateRunnable(circuitBreaker, () -> doAppend(entry))
        );
        task.run();
    }

    private void doAppend(AuditLogEntry entry) {
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.current_tenant_id', ?, true)",
                String.class,
                entry.tenantId().toString()
        );
        jdbcTemplate.update(
                """
                INSERT INTO audit_log
                    (id, tenant_id, actor_id, actor_role, action, resource_type, resource_id, trace_id, metadata, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                entry.id(),
                entry.tenantId(),
                entry.actorId(),
                entry.actorRole(),
                entry.action(),
                entry.resourceType(),
                entry.resourceId(),
                entry.traceId(),
                entry.metadataJson(),
                Timestamp.from(entry.createdAt())
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditLogEntry> findByTenantId(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Supplier<List<AuditLogEntry>> supplier = Bulkhead.decorateSupplier(
                bulkhead,
                CircuitBreaker.decorateSupplier(circuitBreaker, () -> doFindByTenantId(tenantId))
        );
        return supplier.get();
    }

    private List<AuditLogEntry> doFindByTenantId(UUID tenantId) {
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.current_tenant_id', ?, true)",
                String.class,
                tenantId.toString()
        );
        return jdbcTemplate.query(
                """
                SELECT id, tenant_id, actor_id, actor_role, action, resource_type, resource_id, trace_id, metadata::text, created_at
                FROM audit_log
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                """,
                (rs, rowNum) -> new AuditLogEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("actor_id"),
                        rs.getString("actor_role"),
                        rs.getString("action"),
                        rs.getString("resource_type"),
                        rs.getString("resource_id"),
                        rs.getString("trace_id"),
                        rs.getString("metadata"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                tenantId
        );
    }
}
