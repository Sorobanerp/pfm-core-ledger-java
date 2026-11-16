package br.com.pfm.ledger.adapter.out;

import br.com.pfm.ledger.application.port.out.LedgerEntryRepositoryPort;
import br.com.pfm.ledger.application.port.out.TransactionalOutboxPort;
import br.com.pfm.ledger.domain.LedgerEntry;
import br.com.pfm.ledger.domain.Money;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Outbound Adapter implementing PostgreSQL RLS-aware persistence for {@link LedgerEntry}
 * and the ACID Transactional Outbox table {@code outbox_events} (ADR-005 & ADR-006).
 */
@Repository
public class PostgresRlsLedgerRepositoryAdapter implements LedgerEntryRepositoryPort, TransactionalOutboxPort {

    private final JdbcTemplate jdbcTemplate;

    public PostgresRlsLedgerRepositoryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public LedgerEntry save(LedgerEntry entry) {
        final String sql = """
                INSERT INTO ledger_entries (
                    id, tenant_id, account_id, credit_card_id,
                    category_code, subcategory_code, direction, entry_type,
                    amount, currency, competence_date, cash_date,
                    description, idempotency_key, original_transaction_id,
                    reconciliation_status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(
                sql,
                entry.id(),
                entry.tenantId(),
                entry.accountId(),
                entry.creditCardId(),
                entry.categoryCode(),
                entry.subcategoryCode(),
                entry.direction().name(),
                entry.entryType().name(),
                entry.amount().amount(),
                entry.amount().currency(),
                Date.valueOf(entry.competenceDate()),
                entry.cashDate() != null ? Date.valueOf(entry.cashDate()) : null,
                entry.description(),
                entry.idempotencyKey(),
                entry.originalTransactionId(),
                entry.reconciliationStatus().name(),
                Timestamp.from(entry.createdAt())
        );
        return entry;
    }

    @Override
    public Optional<LedgerEntry> findById(UUID id) {
        final String sql = "SELECT * FROM ledger_entries WHERE id = ?";
        List<LedgerEntry> results = jdbcTemplate.query(sql, LEDGER_ENTRY_ROW_MAPPER, id);
        return results.stream().findFirst();
    }

    @Override
    public List<LedgerEntry> findAll() {
        // Notice: ZERO explicit 'WHERE tenant_id = ?' clause here!
        // PostgreSQL Row-Level Security (RLS) enforces strict tenant isolation at the database engine level.
        final String sql = "SELECT * FROM ledger_entries ORDER BY competence_date DESC, created_at DESC";
        return jdbcTemplate.query(sql, LEDGER_ENTRY_ROW_MAPPER);
    }

    @Override
    public List<LedgerEntry> findByCompetenceDateBetween(LocalDate startInclusive, LocalDate endInclusive) {
        final String sql = """
                SELECT * FROM ledger_entries
                WHERE competence_date BETWEEN ? AND ?
                ORDER BY competence_date ASC, created_at ASC
                """;
        return jdbcTemplate.query(
                sql,
                LEDGER_ENTRY_ROW_MAPPER,
                Date.valueOf(startInclusive),
                Date.valueOf(endInclusive)
        );
    }

    @Override
    public void appendOutboxEvent(
            UUID tenantId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String idempotencyKey,
            String payloadJson
    ) {
        final String sql = """
                INSERT INTO outbox_events (
                    id, tenant_id, aggregate_type, aggregate_id,
                    event_type, idempotency_key, payload, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'PENDING', ?)
                """;
        jdbcTemplate.update(
                sql,
                UUID.randomUUID(),
                tenantId,
                aggregateType,
                aggregateId,
                eventType,
                idempotencyKey,
                payloadJson,
                Timestamp.from(Instant.now())
        );
    }

    private static final RowMapper<LedgerEntry> LEDGER_ENTRY_ROW_MAPPER = new RowMapper<>() {
        @Override
        public LedgerEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            Date cashDateSql = rs.getDate("cash_date");
            return new LedgerEntry(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("account_id", UUID.class),
                    rs.getObject("credit_card_id", UUID.class),
                    rs.getString("category_code"),
                    rs.getString("subcategory_code"),
                    LedgerEntry.EntryDirection.valueOf(rs.getString("direction")),
                    LedgerEntry.EntryType.valueOf(rs.getString("entry_type")),
                    Money.of(rs.getBigDecimal("amount"), rs.getString("currency")),
                    rs.getDate("competence_date").toLocalDate(),
                    cashDateSql != null ? cashDateSql.toLocalDate() : null,
                    rs.getString("description"),
                    rs.getString("idempotency_key"),
                    rs.getObject("original_transaction_id", UUID.class),
                    LedgerEntry.ReconciliationStatus.valueOf(rs.getString("reconciliation_status")),
                    rs.getTimestamp("created_at").toInstant()
            );
        }
    };
}
