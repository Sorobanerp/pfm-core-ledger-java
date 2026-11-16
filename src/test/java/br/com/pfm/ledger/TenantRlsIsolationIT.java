package br.com.pfm.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.pfm.ledger.adapter.in.LedgerInboundAdapter;
import br.com.pfm.ledger.domain.LedgerEntry;
import br.com.pfm.ledger.domain.Money;
import br.com.pfm.ledger.infrastructure.TenantContextHolder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
 * Integration Test validating PostgreSQL 16 Row-Level Security (RLS) Zero-Trust Multi-Tenancy (ADR-006 / RN05)
 * and Banking Decimal Precision (RN06) under the restricted {@code pfm_app_user} role.
 */
@SpringBootTest
@Testcontainers
class TenantRlsIsolationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("pfm_core_ledger")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Application connects strictly as restricted `pfm_app_user` (subject to RLS, NOBYPASSRLS)
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "pfm_app_user");
        registry.add("spring.datasource.password", () -> "pfm_app_secret_2026");

        // Flyway runs DDL migrations as database owner `postgres`
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID ACCOUNT_A = UUID.fromString("11111111-aaaa-4aaa-8aaa-111111111111");
    private static final UUID ACCOUNT_B = UUID.fromString("22222222-bbbb-4bbb-8bbb-222222222222");

    @Autowired
    private LedgerInboundAdapter ledgerInboundAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUpTenantsAndAccounts() throws Exception {
        seedTenantIfAbsent(TENANT_A, ACCOUNT_A, "Tenant Alpha Family Office");
        seedTenantIfAbsent(TENANT_B, ACCOUNT_B, "Tenant Beta Private Banking");
    }

    private void seedTenantIfAbsent(UUID tenantId, UUID accountId, String tenantName) throws Exception {
        TenantContextHolder.callWithTenant(tenantId, () ->
                transactionTemplate.execute(status -> {
                    jdbcTemplate.update(
                            """
                            INSERT INTO tenants (id, tenant_id, name, monthly_net_income)
                            VALUES (?, ?, ?, ?)
                            ON CONFLICT (id) DO NOTHING
                            """,
                            tenantId, tenantId, tenantName, new BigDecimal("25000.0000")
                    );
                    jdbcTemplate.update(
                            """
                            INSERT INTO financial_accounts (id, tenant_id, account_name, institution_code, account_type, current_balance)
                            VALUES (?, ?, ?, 'ITAU_341', 'CHECKING', ?)
                            ON CONFLICT (id) DO NOTHING
                            """,
                            accountId, tenantId, "Conta Corrente Principal", new BigDecimal("15000.0000")
                    );
                    return null;
                })
        );
    }

    @Test
    @DisplayName("RN05 / ADR-006: Queries under pfm_app_user with Tenant_A return ZERO rows from Tenant_B and vice-versa")
    void shouldStrictlyIsolateTenantsAndReturnZeroRowsWhenUnbound() throws Exception {
        // Verify connection runs under restricted `pfm_app_user` role
        String currentDbUser = transactionTemplate.execute(
                status -> jdbcTemplate.queryForObject("SELECT current_user", String.class)
        );
        assertThat(currentDbUser).isEqualTo("pfm_app_user");

        // 1. Record a LedgerEntry under Tenant_A
        LedgerEntry entryTenantA = new LedgerEntry(
                UUID.randomUUID(),
                TENANT_A,
                ACCOUNT_A,
                null,
                "ALIMENTACAO",
                "SUPERMERCADO_MERCEARIA",
                LedgerEntry.EntryDirection.DEBIT,
                LedgerEntry.EntryType.STANDARD_EXPENSE,
                Money.brl("450.7500"),
                LocalDate.of(2026, 11, 16),
                LocalDate.of(2026, 11, 16),
                "Supermercado Pao de Acucar - Tenant A",
                "sha256-tenant-a-tx-001",
                null,
                LedgerEntry.ReconciliationStatus.CONCILED,
                Instant.now()
        );
        ledgerInboundAdapter.handleRecordEntry(TENANT_A, entryTenantA);

        // 2. Record a LedgerEntry under Tenant_B
        LedgerEntry entryTenantB = new LedgerEntry(
                UUID.randomUUID(),
                TENANT_B,
                ACCOUNT_B,
                null,
                "MORADIA",
                "ENERGIA_ELETRICA",
                LedgerEntry.EntryDirection.DEBIT,
                LedgerEntry.EntryType.STANDARD_EXPENSE,
                Money.brl("890.1234"),
                LocalDate.of(2026, 11, 16),
                LocalDate.of(2026, 11, 16),
                "Enel Energia - Tenant B",
                "sha256-tenant-b-tx-001",
                null,
                LedgerEntry.ReconciliationStatus.CONCILED,
                Instant.now()
        );
        ledgerInboundAdapter.handleRecordEntry(TENANT_B, entryTenantB);

        // 3. Query as Tenant_A: must return ONLY Tenant_A records and ZERO records from Tenant_B
        List<LedgerEntry> tenantAEntries = ledgerInboundAdapter.handleQueryTenantEntries(TENANT_A);
        assertThat(tenantAEntries)
                .isNotEmpty()
                .allMatch(e -> e.tenantId().equals(TENANT_A))
                .noneMatch(e -> e.tenantId().equals(TENANT_B));
        assertThat(tenantAEntries.getFirst().amount()).isEqualTo(Money.brl("450.7500"));

        // 4. Query as Tenant_B: must return ONLY Tenant_B records and ZERO records from Tenant_A
        List<LedgerEntry> tenantBEntries = ledgerInboundAdapter.handleQueryTenantEntries(TENANT_B);
        assertThat(tenantBEntries)
                .isNotEmpty()
                .allMatch(e -> e.tenantId().equals(TENANT_B))
                .noneMatch(e -> e.tenantId().equals(TENANT_A));
        assertThat(tenantBEntries.getFirst().amount()).isEqualTo(Money.brl("890.1234"));

        // 5. Query WITHOUT tenant_id bound in ScopedValue context: must return ZERO rows across all RLS tables
        List<Map<String, Object>> unboundLedgerRows = transactionTemplate.execute(
                status -> jdbcTemplate.queryForList("SELECT * FROM ledger_entries")
        );
        List<Map<String, Object>> unboundOutboxRows = transactionTemplate.execute(
                status -> jdbcTemplate.queryForList("SELECT * FROM outbox_events")
        );
        List<Map<String, Object>> unboundTenantRows = transactionTemplate.execute(
                status -> jdbcTemplate.queryForList("SELECT * FROM tenants")
        );

        assertThat(unboundLedgerRows).isEmpty();
        assertThat(unboundOutboxRows).isEmpty();
        assertThat(unboundTenantRows).isEmpty();
    }

    @Test
    @DisplayName("Artefato 01 V2 & RN06: Verify Official 2-Level Category Taxonomy Seed and Money Split Residue on 1st Installment")
    void shouldSeedOfficialTwoLevelTaxonomyAndSplitInstallmentsWithExactResidue() {
        Integer categoryCount = transactionTemplate.execute(
                status -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM categories", Integer.class)
        );
        assertThat(categoryCount).isEqualTo(56);

        // RN06: R$ 100.00 split in 3 installments -> 1st = 33.3400, 2nd = 33.3300, 3rd = 33.3300
        Money total = Money.brl("100.00");
        List<Money> installments = total.splitIntoInstallments(3);
        assertThat(installments).hasSize(3);
        assertThat(installments.get(0)).isEqualTo(Money.brl("33.3400"));
        assertThat(installments.get(1)).isEqualTo(Money.brl("33.3300"));
        assertThat(installments.get(2)).isEqualTo(Money.brl("33.3300"));
        assertThat(installments.get(0).add(installments.get(1)).add(installments.get(2))).isEqualTo(total);
    }
}
