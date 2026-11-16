package br.com.pfm.ledger.adapter.out;

import br.com.pfm.ledger.application.port.out.LedgerEntryRepositoryPort;
import br.com.pfm.ledger.application.port.out.TransactionalOutboxPort;
import br.com.pfm.ledger.domain.CreditCard;
import br.com.pfm.ledger.domain.InstallmentSchedule;
import br.com.pfm.ledger.domain.LedgerEntry;
import br.com.pfm.ledger.domain.Money;
import br.com.pfm.ledger.domain.ReceiptReconciliationEngine;
import br.com.pfm.ledger.infrastructure.TenantContextHolder;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Outbound Adapter implementing PostgreSQL RLS-aware persistence for {@link LedgerEntry},
 * {@link InstallmentSchedule}, {@link CreditCard}, {@code receipt_items}, and the ACID
 * Transactional Outbox table {@code outbox_events} (ADR-005 & ADR-006).
 * Explicitly enforces {@code SET LOCAL app.current_tenant_id = '<uuid>'} before every operation.
 */
@Repository
public class PostgresRlsLedgerRepositoryAdapter implements LedgerEntryRepositoryPort, TransactionalOutboxPort {

    private final JdbcTemplate jdbcTemplate;

    public PostgresRlsLedgerRepositoryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private void enforceRlsTenantSession() {
        Optional<UUID> tenantIdOpt = TenantContextHolder.getTenantId();
        if (tenantIdOpt.isPresent()) {
            String safeUuid = UUID.fromString(tenantIdOpt.get().toString()).toString();
            jdbcTemplate.execute("SET LOCAL app.current_tenant_id = '" + safeUuid + "'");
        } else {
            jdbcTemplate.execute("SET LOCAL app.current_tenant_id = ''");
        }
    }

    @Override
    public LedgerEntry save(LedgerEntry entry) {
        enforceRlsTenantSession();
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
        enforceRlsTenantSession();
        final String sql = "SELECT * FROM ledger_entries WHERE id = ?";
        List<LedgerEntry> results = jdbcTemplate.query(sql, LEDGER_ENTRY_ROW_MAPPER, id);
        return results.stream().findFirst();
    }

    @Override
    public Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey) {
        enforceRlsTenantSession();
        final String sql = "SELECT * FROM ledger_entries WHERE idempotency_key = ?";
        List<LedgerEntry> results = jdbcTemplate.query(sql, LEDGER_ENTRY_ROW_MAPPER, idempotencyKey);
        return results.stream().findFirst();
    }

    @Override
    public LedgerEntry updateReconciliationStatus(UUID entryId, LedgerEntry.ReconciliationStatus status) {
        enforceRlsTenantSession();
        final String sql = "UPDATE ledger_entries SET reconciliation_status = ? WHERE id = ?";
        jdbcTemplate.update(sql, status.name(), entryId);
        return findById(entryId).orElseThrow(() -> new IllegalStateException("LedgerEntry not found: " + entryId));
    }

    @Override
    public void saveInstallmentSchedules(List<InstallmentSchedule> schedules) {
        if (schedules == null || schedules.isEmpty()) {
            return;
        }
        enforceRlsTenantSession();
        final String sql = """
                INSERT INTO installment_schedules (
                    id, tenant_id, credit_card_id, parent_ledger_entry_id,
                    installment_number, total_installments, competence_month,
                    due_date, amount, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        Instant now = Instant.now();
        for (InstallmentSchedule s : schedules) {
            jdbcTemplate.update(
                    sql,
                    s.id(),
                    s.tenantId(),
                    s.creditCardId(),
                    s.parentLedgerEntryId(),
                    s.installmentNumber(),
                    s.totalInstallments(),
                    s.competenceMonth(),
                    Date.valueOf(s.dueDate()),
                    s.amount().amount(),
                    s.status().name(),
                    Timestamp.from(now)
            );
        }
    }

    @Override
    public List<InstallmentSchedule> findInstallmentSchedulesByMonth(String competenceMonth) {
        enforceRlsTenantSession();
        final String sql = """
                SELECT * FROM installment_schedules
                WHERE competence_month = ?
                ORDER BY installment_number ASC
                """;
        return jdbcTemplate.query(sql, INSTALLMENT_SCHEDULE_ROW_MAPPER, competenceMonth);
    }

    @Override
    public List<InstallmentSchedule> findAllInstallmentSchedules() {
        enforceRlsTenantSession();
        final String sql = "SELECT * FROM installment_schedules ORDER BY competence_month ASC, installment_number ASC";
        return jdbcTemplate.query(sql, INSTALLMENT_SCHEDULE_ROW_MAPPER);
    }

    @Override
    public void saveReceiptItems(List<ReceiptReconciliationEngine.ReceiptItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        enforceRlsTenantSession();
        final String sql = """
                INSERT INTO receipt_items (
                    id, tenant_id, ledger_entry_id, access_key, item_sequence,
                    ean, description, quantity, unit_of_measure, unit_price,
                    total_price, issued_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        Instant now = Instant.now();
        for (ReceiptReconciliationEngine.ReceiptItem item : items) {
            jdbcTemplate.update(
                    sql,
                    item.id(),
                    item.tenantId(),
                    item.ledgerEntryId(),
                    item.accessKey(),
                    item.itemSequence(),
                    item.ean(),
                    item.description(),
                    item.quantity(),
                    item.unitOfMeasure(),
                    item.unitPrice().amount(),
                    item.totalPrice().amount(),
                    Timestamp.from(item.issuedAt()),
                    Timestamp.from(now)
            );
        }
    }

    @Override
    public Map<String, ReceiptReconciliationEngine.HistoricalEanPrice> findLatestPricesByEan(List<String> eans) {
        if (eans == null || eans.isEmpty()) {
            return Map.of();
        }
        enforceRlsTenantSession();
        Map<String, ReceiptReconciliationEngine.HistoricalEanPrice> result = new HashMap<>();
        final String sql = """
                SELECT ean, description, unit_price, issued_at
                FROM receipt_items
                WHERE ean = ?
                ORDER BY issued_at DESC
                LIMIT 1
                """;
        for (String ean : eans) {
            List<ReceiptReconciliationEngine.HistoricalEanPrice> rows = jdbcTemplate.query(
                    sql,
                    (rs, rowNum) -> new ReceiptReconciliationEngine.HistoricalEanPrice(
                            rs.getString("ean"),
                            rs.getString("description"),
                            Money.brl(rs.getBigDecimal("unit_price")),
                            rs.getTimestamp("issued_at").toInstant().atZone(ZoneOffset.UTC).toLocalDate(),
                            "Histórico Tenant"
                    ),
                    ean
            );
            if (!rows.isEmpty()) {
                result.put(ean, rows.getFirst());
            }
        }
        return result;
    }

    @Override
    public List<CreditCard> findAllCreditCards() {
        enforceRlsTenantSession();
        final String sql = "SELECT * FROM credit_cards ORDER BY card_name ASC";
        return jdbcTemplate.query(sql, CREDIT_CARD_ROW_MAPPER);
    }

    @Override
    public Optional<CreditCard> findCreditCardById(UUID cardId) {
        enforceRlsTenantSession();
        final String sql = "SELECT * FROM credit_cards WHERE id = ?";
        return jdbcTemplate.query(sql, CREDIT_CARD_ROW_MAPPER, cardId).stream().findFirst();
    }

    @Override
    public Money findTenantMonthlyNetIncome(UUID tenantId) {
        enforceRlsTenantSession();
        final String sql = "SELECT monthly_net_income, base_currency FROM tenants WHERE tenant_id = ?";
        List<Money> list = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> Money.of(rs.getBigDecimal("monthly_net_income"), rs.getString("base_currency")),
                tenantId
        );
        return list.isEmpty() ? Money.zeroBrl() : list.getFirst();
    }

    @Override
    public List<LedgerEntry> findAll() {
        enforceRlsTenantSession();
        // PostgreSQL Row-Level Security (RLS) enforces strict tenant isolation at the database engine level.
        final String sql = "SELECT * FROM ledger_entries ORDER BY competence_date DESC, created_at DESC";
        return jdbcTemplate.query(sql, LEDGER_ENTRY_ROW_MAPPER);
    }

    @Override
    public List<LedgerEntry> findByCompetenceDateBetween(LocalDate startInclusive, LocalDate endInclusive) {
        enforceRlsTenantSession();
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
        appendOutboxEvent(tenantId, aggregateType, aggregateId, eventType, Money.zeroBrl(), idempotencyKey, payloadJson);
    }

    @Override
    public void appendOutboxEvent(
            UUID tenantId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            Money monetaryAmount,
            String idempotencyKey,
            String payloadJson
    ) {
        enforceRlsTenantSession();
        final String sql = """
                INSERT INTO outbox_events (
                    id, tenant_id, aggregate_type, aggregate_id,
                    event_type, monetary_amount, idempotency_key, payload, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'PENDING', ?)
                """;
        jdbcTemplate.update(
                sql,
                UUID.randomUUID(),
                tenantId,
                aggregateType,
                aggregateId,
                eventType,
                monetaryAmount != null ? monetaryAmount.amount() : BigDecimal.ZERO.setScale(4),
                idempotencyKey,
                payloadJson,
                Timestamp.from(Instant.now())
        );
    }

    @Override
    public List<OutboxEventRecord> findPendingEvents(int limit) {
        enforceRlsTenantSession();
        final String sql = """
                SELECT id, tenant_id, aggregate_type, aggregate_id, event_type,
                       monetary_amount, idempotency_key, payload::text AS payload_json,
                       status, created_at, published_at
                FROM outbox_events
                WHERE status = 'PENDING'
                ORDER BY created_at ASC
                LIMIT ?
                """;
        return jdbcTemplate.query(sql, OUTBOX_ROW_MAPPER, limit);
    }

    @Override
    public void markEventPublished(UUID eventId, Instant publishedAt) {
        enforceRlsTenantSession();
        final String sql = """
                UPDATE outbox_events
                SET status = 'PUBLISHED', published_at = ?
                WHERE id = ?
                """;
        jdbcTemplate.update(sql, Timestamp.from(publishedAt), eventId);
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

    private static final RowMapper<InstallmentSchedule> INSTALLMENT_SCHEDULE_ROW_MAPPER = (rs, rowNum) ->
            new InstallmentSchedule(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("credit_card_id", UUID.class),
                    rs.getObject("parent_ledger_entry_id", UUID.class),
                    rs.getInt("installment_number"),
                    rs.getInt("total_installments"),
                    rs.getString("competence_month"),
                    rs.getDate("due_date").toLocalDate(),
                    Money.brl(rs.getBigDecimal("amount")),
                    InstallmentSchedule.InstallmentStatus.valueOf(rs.getString("status"))
            );

    private static final RowMapper<CreditCard> CREDIT_CARD_ROW_MAPPER = (rs, rowNum) ->
            new CreditCard(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("financial_account_id", UUID.class),
                    rs.getString("card_name"),
                    rs.getString("card_last_four"),
                    Money.brl(rs.getBigDecimal("credit_limit")),
                    Money.brl(rs.getBigDecimal("available_limit")),
                    rs.getInt("closing_day"),
                    rs.getInt("due_day")
            );

    private static final RowMapper<OutboxEventRecord> OUTBOX_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp pubTs = rs.getTimestamp("published_at");
        return new OutboxEventRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("event_type"),
                Money.brl(rs.getBigDecimal("monetary_amount")),
                rs.getString("idempotency_key"),
                rs.getString("payload_json"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                pubTs != null ? pubTs.toInstant() : null
        );
    };
}
