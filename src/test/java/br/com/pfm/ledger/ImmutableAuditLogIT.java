package br.com.pfm.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.pfm.ledger.application.port.in.TenantAdminUseCase;
import br.com.pfm.ledger.application.port.in.UserProfileUseCase;
import br.com.pfm.ledger.application.port.out.AuditLogRepositoryPort;
import br.com.pfm.ledger.domain.AuditLogEntry;
import br.com.pfm.ledger.domain.FamilyRole;
import br.com.pfm.ledger.domain.Money;
import br.com.pfm.ledger.domain.SubscriptionPlan;
import br.com.pfm.ledger.infrastructure.TenantContextHolder;
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
 * Integration Test for ADR-010 Immutable Audit Log (Flyway V3),
 * PostgreSQL FORCE RLS isolation and DO INSTEAD NOTHING rules.
 */
@SpringBootTest
@Testcontainers
class ImmutableAuditLogIT {

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

    private static final UUID TENANT_1 = UUID.fromString("aaaaaaaa-1111-4111-8111-111111111111");
    private static final UUID TENANT_2 = UUID.fromString("bbbbbbbb-2222-4222-8222-222222222222");

    @Autowired
    private TenantAdminUseCase tenantAdminUseCase;

    @Autowired
    private UserProfileUseCase userProfileUseCase;

    @Autowired
    private AuditLogRepositoryPort auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setupTenants() throws Exception {
        seedTenant(TENANT_1, "Audit Tenant 1");
        seedTenant(TENANT_2, "Audit Tenant 2");
    }

    private void seedTenant(UUID tenantId, String name) throws Exception {
        TenantContextHolder.callWithTenant(tenantId, () ->
                transactionTemplate.execute(status -> {
                    jdbcTemplate.update(
                            """
                            INSERT INTO tenants (id, tenant_id, name, base_currency, monthly_net_income, status, plan)
                            VALUES (?, ?, ?, 'BRL', 12000.0000, 'ACTIVE', 'FREE')
                            ON CONFLICT (tenant_id) DO NOTHING
                            """,
                            tenantId, tenantId, name
                    );
                    return null;
                })
        );
    }

    @Test
    @DisplayName("ADR-010: US-12 & US-13 actions record immutable audit_log entries isolated by RLS")
    void shouldRecordImmutableAuditLogAndEnforceRls() throws Exception {
        TenantContextHolder.callWithTenant(TENANT_1, () -> {
            // US-12: Upgrade Tenant 1 to FAMILY_PLUS
            tenantAdminUseCase.changeTenantPlan(TENANT_1, SubscriptionPlan.FAMILY_PLUS);

            // US-13: Invite member and set category budget for Tenant 1
            userProfileUseCase.inviteFamilyMember(
                    TENANT_1, "familia@pfm.local", "Membro Teste", FamilyRole.MEMBER, Money.of("1500.0000", "BRL")
            );
            userProfileUseCase.setCategoryBudget(
                    TENANT_1, "ALIMENTACAO", "Alimentação", Money.of("2000.0000", "BRL"), 80
            );
            return null;
        });

        // Verify Tenant 1 has 3 audit entries with non-blank trace_id
        List<AuditLogEntry> tenant1Logs = TenantContextHolder.callWithTenant(
                TENANT_1, () -> auditLogRepository.findByTenantId(TENANT_1)
        );
        assertThat(tenant1Logs).hasSizeGreaterThanOrEqualTo(3);
        assertThat(tenant1Logs)
                .extracting(AuditLogEntry::action)
                .contains("TENANT_PLAN_CHANGED", "FAMILY_MEMBER_INVITED", "CATEGORY_BUDGET_SET");
        assertThat(tenant1Logs).allSatisfy(entry -> assertThat(entry.traceId()).isNotBlank());

        // Verify Tenant 2 cannot see Tenant 1's audit entries via RLS
        List<AuditLogEntry> tenant2Logs = TenantContextHolder.callWithTenant(
                TENANT_2, () -> auditLogRepository.findByTenantId(TENANT_2)
        );
        assertThat(tenant2Logs).isEmpty();
    }

    @Test
    @DisplayName("ADR-010: PostgreSQL DO INSTEAD NOTHING rules prevent UPDATE and DELETE on audit_log")
    void shouldPreventUpdateAndDeleteOnAuditLog() throws Exception {
        AuditLogEntry entry = AuditLogEntry.of(
                TENANT_1,
                "OWNER",
                "OWNER",
                "TEST_IMMUTABILITY",
                "AUDIT",
                "res-1",
                "trace-immutable-001",
                "{\"immutable\":true}"
        );
        TenantContextHolder.callWithTenant(TENANT_1, () -> {
            auditLogRepository.append(entry);
            return null;
        });

        // Attempt UPDATE and DELETE as postgres superuser directly to test PostgreSQL RULE DO INSTEAD NOTHING
        try (var conn = POSTGRES.createConnection(""); var stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE audit_log SET action = 'TAMPERED' WHERE id = '" + entry.id() + "'");
            stmt.executeUpdate("DELETE FROM audit_log WHERE id = '" + entry.id() + "'");
        }

        List<AuditLogEntry> entries = TenantContextHolder.callWithTenant(
                TENANT_1, () -> auditLogRepository.findByTenantId(TENANT_1)
        );
        assertThat(entries)
                .filteredOn(e -> e.id().equals(entry.id()))
                .singleElement()
                .satisfies(e -> assertThat(e.action()).isEqualTo("TEST_IMMUTABILITY"));
    }
}
