package br.com.pfm.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.pfm.ledger.adapter.in.grpc.CoreLedgerGrpcServerAdapter;
import br.com.pfm.ledger.adapter.in.kafka.CategorizedTransactionKafkaConsumer;
import br.com.pfm.ledger.adapter.out.postgres.OutboxRelayScheduler;
import br.com.pfm.ledger.application.port.in.CommitCategorizedTransactionUseCase.CategorizedTransactionCommand;
import br.com.pfm.ledger.application.port.in.CommitCategorizedTransactionUseCase.CommitTransactionResult;
import br.com.pfm.ledger.application.port.in.GetTenantFinancialSummaryUseCase.TenantFinancialSummaryOutput;
import br.com.pfm.ledger.application.port.in.ProcessReceiptUseCase.ProcessReceiptCommand;
import br.com.pfm.ledger.application.port.in.ProcessReceiptUseCase.ProcessReceiptOutput;
import br.com.pfm.ledger.application.port.in.ProcessReceiptUseCase.ReceiptLineItemDto;
import br.com.pfm.ledger.domain.LedgerEntry;
import br.com.pfm.ledger.domain.Money;
import br.com.pfm.ledger.infrastructure.TenantContextHolder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration Test validating ACID persistence + PostgreSQL 16 RLS + Transactional Outbox Relay
 * + Installment Splitting + Refund + NFC-e Reconciliation on Java 25 Virtual Threads & ScopedValue.
 */
@SpringBootTest
@Testcontainers
class CoreLedgerOutboxIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("pfm_core_ledger")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "pfm_app_user");
        registry.add("spring.datasource.password", () -> "pfm_app_secret_2026");

        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    private static final UUID TENANT_DANTAS = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private static final UUID OTHER_TENANT = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-dddd-4ddd-8ddd-111111111111");
    private static final UUID CARD_ID = UUID.fromString("22222222-dddd-4ddd-8ddd-222222222222");

    @Autowired
    private CategorizedTransactionKafkaConsumer kafkaConsumer;

    @Autowired
    private CoreLedgerGrpcServerAdapter grpcAdapter;

    @Autowired
    private OutboxRelayScheduler outboxRelayScheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() throws Exception {
        outboxRelayScheduler.clearPublishedEventsLog();
        TenantContextHolder.callWithTenant(TENANT_DANTAS, () ->
                transactionTemplate.execute(status -> {
                    jdbcTemplate.update(
                            """
                            INSERT INTO tenants (id, tenant_id, name, monthly_net_income)
                            VALUES (?, ?, 'Tenant Dantas PFM', 10000.0000)
                            ON CONFLICT (id) DO NOTHING
                            """,
                            TENANT_DANTAS, TENANT_DANTAS
                    );
                    jdbcTemplate.update(
                            """
                            INSERT INTO financial_accounts (id, tenant_id, account_name, institution_code, account_type, current_balance)
                            VALUES (?, ?, 'Conta Itaú Personnalité', 'ITAU_341', 'CHECKING', 15000.0000)
                            ON CONFLICT (id) DO NOTHING
                            """,
                            ACCOUNT_ID, TENANT_DANTAS
                    );
                    jdbcTemplate.update(
                            """
                            INSERT INTO credit_cards (id, tenant_id, financial_account_id, card_name, card_last_four, credit_limit, available_limit, closing_day, due_day)
                            VALUES (?, ?, ?, 'Itaú Personnalité Black', '9874', 35000.0000, 35000.0000, 25, 5)
                            ON CONFLICT (id) DO NOTHING
                            """,
                            CARD_ID, TENANT_DANTAS, ACCOUNT_ID
                    );
                    return null;
                })
        );
    }

    @Test
    @DisplayName("US-07 & US-08 IT: Escrita ACID + RLS + Parcelamento 10x + Estorno -R$ 168,94 + Conciliação NFC-e ±3d + Outbox Relay")
    void shouldCommitCategorizedTransactionsReconcileReceiptAndRelayOutboxUnderRls() throws Exception {
        // 1. Consume a 10x parcelled purchase (R$ 1.000,03) on Virtual Thread with ScopedValue
        CategorizedTransactionCommand purchase10xCmd = new CategorizedTransactionCommand(
                TENANT_DANTAS,
                ACCOUNT_ID,
                CARD_ID,
                "sha256-it-purchase-10x-001",
                "LAZER",
                "VESTUARIO_CALCADOS",
                new BigDecimal("1000.0300"),
                "BRL",
                LocalDate.of(2026, 3, 10),
                "LOJAS RENNER PARC 01/10",
                1,
                10,
                5,
                null
        );

        CommitTransactionResult commit10x = kafkaConsumer.consumeCategorizedTransaction(purchase10xCmd);
        assertThat(commit10x.idempotentReplay()).isFalse();
        assertThat(commit10x.generatedSchedules()).hasSize(10);
        assertThat(commit10x.generatedSchedules().get(9).amount()).isEqualTo(Money.brl("100.0300"));
        assertThat(commit10x.resultingCategorySpend()).isEqualTo(Money.brl("1000.0300"));

        // Verify idempotency replay does not duplicate ledger entry or outbox event
        CommitTransactionResult replay10x = kafkaConsumer.consumeCategorizedTransaction(purchase10xCmd);
        assertThat(replay10x.idempotentReplay()).isTrue();
        assertThat(replay10x.primaryEntry().id()).isEqualTo(commit10x.primaryEntry().id());

        // 2. Consume a Refund of -R$ 168,94 on the same category (LAZER / VESTUARIO_CALCADOS)
        CategorizedTransactionCommand refundCmd = new CategorizedTransactionCommand(
                TENANT_DANTAS,
                ACCOUNT_ID,
                CARD_ID,
                "sha256-it-refund-16894",
                "LAZER",
                "VESTUARIO_CALCADOS",
                new BigDecimal("-168.9400"),
                "BRL",
                LocalDate.of(2026, 3, 18),
                "ESTORNO LOJAS RENNER",
                1,
                1,
                5,
                commit10x.primaryEntry().id()
        );

        CommitTransactionResult refundResult = kafkaConsumer.consumeCategorizedTransaction(refundCmd);
        assertThat(refundResult.primaryEntry().entryType()).isEqualTo(LedgerEntry.EntryType.REFUND_CREDIT);
        assertThat(refundResult.primaryEntry().direction()).isEqualTo(LedgerEntry.EntryDirection.CREDIT);
        assertThat(refundResult.resultingCategorySpend()).isEqualTo(Money.brl("831.0900"));

        // 3. Consume a Supermarket statement purchase (R$ 125,80 on 2026-03-16) and reconcile with NFC-e issued 2026-03-14 (±2 days)
        CategorizedTransactionCommand groceryCmd = new CategorizedTransactionCommand(
                TENANT_DANTAS,
                ACCOUNT_ID,
                CARD_ID,
                "sha256-it-grocery-atacadao",
                "ALIMENTACAO",
                "SUPERMERCADO_MERCEARIA",
                new BigDecimal("125.8000"),
                "BRL",
                LocalDate.of(2026, 3, 16),
                "ATACADAO SA",
                1,
                1,
                5,
                null
        );
        CommitTransactionResult groceryCommit = kafkaConsumer.consumeCategorizedTransaction(groceryCmd);
        assertThat(groceryCommit.primaryEntry().reconciliationStatus()).isEqualTo(LedgerEntry.ReconciliationStatus.PENDING);

        // Seed previous EAN purchase at R$ 20,00 so current R$ 23,50 triggers >10% inflation alert
        ProcessReceiptCommand baselineReceiptCmd = new ProcessReceiptCommand(
                TENANT_DANTAS,
                "sha256-it-receipt-baseline",
                "24260212345678000199650010000123451000111111",
                "12345678000199",
                "ATACADAO SA",
                Instant.parse("2026-02-10T10:00:00Z"),
                "40.0000",
                "BRL",
                CARD_ID,
                ACCOUNT_ID,
                List.of(new ReceiptLineItemDto(
                        1,
                        "7891000100103",
                        "CAFE TORRADO 500G",
                        "2.0000",
                        "UN",
                        "20.0000",
                        "40.0000"
                ))
        );
        grpcAdapter.processReceipt(baselineReceiptCmd);

        // Process current NFC-e receipt (2026-03-14, R$ 125,80 -> matches statement entry from 2026-03-16)
        ProcessReceiptCommand currentReceiptCmd = new ProcessReceiptCommand(
                TENANT_DANTAS,
                "sha256-it-receipt-current",
                "24260312345678000199650010000123451000222222",
                "12345678000199",
                "ATACADAO SA",
                Instant.parse("2026-03-14T15:30:00Z"),
                "125.8000",
                "BRL",
                CARD_ID,
                ACCOUNT_ID,
                List.of(new ReceiptLineItemDto(
                        1,
                        "7891000100103",
                        "CAFE TORRADO 500G",
                        "2.0000",
                        "UN",
                        "23.5000",
                        "47.0000"
                ))
        );
        ProcessReceiptOutput receiptOutput = grpcAdapter.processReceipt(currentReceiptCmd);
        assertThat(receiptOutput.reconciliationStatus()).isEqualTo("MATCHED_STATEMENT_LINE");
        assertThat(receiptOutput.matchedTransactionId()).isEqualTo(groceryCommit.primaryEntry().id());
        assertThat(receiptOutput.hasInflationAlerts()).isTrue();
        assertThat(receiptOutput.inflationAlerts()).hasSize(1);
        assertThat(receiptOutput.inflationAlerts().getFirst().inflationPercentage())
                .isEqualByComparingTo(new BigDecimal("17.5000"));

        // 4. Verify Outbox Relay Scheduler publishes the 3 committed events for TENANT_DANTAS and ZERO for OTHER_TENANT
        int relayedForOtherTenant = outboxRelayScheduler.relayPendingEventsForTenant(OTHER_TENANT, 50);
        assertThat(relayedForOtherTenant).isZero();

        int relayedForDantas = outboxRelayScheduler.relayPendingEventsForTenant(TENANT_DANTAS, 50);
        assertThat(relayedForDantas).isEqualTo(3);
        assertThat(outboxRelayScheduler.getPublishedEventsLog())
                .hasSize(3)
                .allMatch(evt -> OutboxRelayScheduler.LEDGER_COMMITTED_TOPIC.equals(evt.topic()))
                .allMatch(evt -> TENANT_DANTAS.toString().equals(evt.partitionKey()));

        // 5. Verify GetTenantFinancialSummary gRPC endpoint returns Multi-Card Matrix and Future Commitment timeline
        TenantFinancialSummaryOutput summary = grpcAdapter.getTenantFinancialSummary(TENANT_DANTAS, "2026-03", "BRL");
        assertThat(summary.tenantId()).isEqualTo(TENANT_DANTAS.toString());
        assertThat(summary.cardMatrix()).hasSize(1);
        assertThat(summary.futureCommitmentTimeline()).hasSize(12);
    }
}
