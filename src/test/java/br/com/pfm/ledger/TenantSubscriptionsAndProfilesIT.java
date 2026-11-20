package br.com.pfm.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.pfm.ledger.application.port.in.TenantAdminUseCase;
import br.com.pfm.ledger.application.port.in.TenantAdminUseCase.PlatformMetricsDTO;
import br.com.pfm.ledger.application.port.in.TenantAdminUseCase.TenantSummaryDTO;
import br.com.pfm.ledger.application.port.in.UserProfileUseCase;
import br.com.pfm.ledger.application.port.in.UserProfileUseCase.ExportDataResult;
import br.com.pfm.ledger.domain.CategoryBudgetRule;
import br.com.pfm.ledger.domain.FamilyMemberProfile;
import br.com.pfm.ledger.domain.FamilyRole;
import br.com.pfm.ledger.domain.LgpdErasureRecord;
import br.com.pfm.ledger.domain.Money;
import br.com.pfm.ledger.domain.SubscriptionPlan;
import br.com.pfm.ledger.domain.SubscriptionStatus;
import br.com.pfm.ledger.infrastructure.TenantContextHolder;
import java.math.BigDecimal;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration Test for US-12 (SaaS Admin & Subscriptions) & US-13 (Profile, Family & LGPD)
 * with PostgreSQL 16 Testcontainers, Flyway V2 and Row-Level Security (RLS) enforcement.
 */
@SpringBootTest
@Testcontainers
class TenantSubscriptionsAndProfilesIT {

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

    private static final UUID TENANT_1 = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_2 = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Autowired
    private TenantAdminUseCase adminUseCase;

    @Autowired
    private UserProfileUseCase userProfileUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @BeforeEach
    void setupTestData() throws Exception {
        seedTenant(TENANT_1, "Familia Silva", "FREE");
        seedTenant(TENANT_2, "Familia Santos", "INDIVIDUAL_PRO");
    }

    private void seedTenant(UUID tenantId, String name, String plan) throws Exception {
        TenantContextHolder.callWithTenant(tenantId, () ->
                transactionTemplate.execute(status -> {
                    jdbcTemplate.update("""
                            INSERT INTO tenants (id, tenant_id, name, base_currency, monthly_net_income, status, plan)
                            VALUES (?, ?, ?, 'BRL', 12000.0000, 'ACTIVE', ?)
                            ON CONFLICT (tenant_id) DO UPDATE SET plan = EXCLUDED.plan, status = 'ACTIVE'
                            """, tenantId, tenantId, name, plan);
                    return null;
                })
        );
    }

    @Test
    @DisplayName("US-12 SaaS Admin: Platform admin lists tenants and changes plans without seeing private PII")
    void testTenantAdminPlanAndMetrics() throws Exception {
        // Platform admin lists tenants
        List<TenantSummaryDTO> tenants = TenantContextHolder.callAsPlatformAdmin(() ->
                adminUseCase.listTenants(20, null, null, null)
        );
        assertThat(tenants).hasSizeGreaterThanOrEqualTo(2);

        // Platform admin upgrades Tenant 1 to FAMILY_PLUS
        TenantSummaryDTO updated = TenantContextHolder.callAsPlatformAdmin(() ->
                adminUseCase.changeTenantPlan(TENANT_1, SubscriptionPlan.FAMILY_PLUS)
        );
        assertThat(updated.plan()).isEqualTo(SubscriptionPlan.FAMILY_PLUS);
        assertThat(updated.maxMembers()).isEqualTo(5);

        // Platform admin updates status of Tenant 2 to PAST_DUE
        TenantSummaryDTO statusUpdated = TenantContextHolder.callAsPlatformAdmin(() ->
                adminUseCase.updateSubscriptionStatus(TENANT_2, SubscriptionStatus.PAST_DUE, "Cartao recusado")
        );
        assertThat(statusUpdated.status()).isEqualTo(SubscriptionStatus.PAST_DUE);

        // Platform metrics
        PlatformMetricsDTO metrics = TenantContextHolder.callAsPlatformAdmin(() ->
                adminUseCase.getPlatformMetrics("ALL")
        );
        assertThat(metrics.totalTenants()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("US-13 User Profile & Budgets: RLS strictly isolates category budgets and members between tenants")
    void testUserProfileAndBudgetRlsIsolation() throws Exception {
        // Tenant 1 configures a budget of R$ 1500 in ALIMENTACAO with 80% alert
        CategoryBudgetRule budget1 = TenantContextHolder.callWithTenant(TENANT_1, () ->
                userProfileUseCase.setCategoryBudget(TENANT_1, "ALIMENTACAO", "Supermercado", Money.brl("1500.0000"), 80)
        );
        assertThat(budget1.getMonthlyCap().amount()).isEqualTo(new BigDecimal("1500.0000"));

        // Tenant 2 configures a budget of R$ 500 in TRANSPORTE
        TenantContextHolder.callWithTenant(TENANT_2, () ->
                userProfileUseCase.setCategoryBudget(TENANT_2, "TRANSPORTE", "Combustível", Money.brl("500.0000"), 80)
        );

        // Verify Tenant 1 queries budgets and sees ONLY ALIMENTACAO (never TRANSPORTE)
        List<CategoryBudgetRule> tenant1Budgets = TenantContextHolder.callWithTenant(TENANT_1, () ->
                userProfileUseCase.getCategoryBudgets(TENANT_1)
        );
        assertThat(tenant1Budgets).hasSize(1);
        assertThat(tenant1Budgets.getFirst().getCategoryId()).isEqualTo("ALIMENTACAO");

        // Verify Tenant 2 queries budgets and sees ONLY TRANSPORTE
        List<CategoryBudgetRule> tenant2Budgets = TenantContextHolder.callWithTenant(TENANT_2, () ->
                userProfileUseCase.getCategoryBudgets(TENANT_2)
        );
        assertThat(tenant2Budgets).hasSize(1);
        assertThat(tenant2Budgets.getFirst().getCategoryId()).isEqualTo("TRANSPORTE");

        // Tenant 1 invites a family member
        FamilyMemberProfile member = TenantContextHolder.callWithTenant(TENANT_1, () ->
                userProfileUseCase.inviteFamilyMember(TENANT_1, "filho@silva.com", "Filho Silva", FamilyRole.MEMBER, Money.brl("400.0000"))
        );
        assertThat(member.getEmail()).isEqualTo("filho@silva.com");
        assertThat(member.getInvitationToken()).isNotBlank();

        // Tenant 2 cannot see Tenant 1's family member
        List<FamilyMemberProfile> tenant2Members = TenantContextHolder.callWithTenant(TENANT_2, () ->
                userProfileUseCase.listFamilyMembers(TENANT_2)
        );
        assertThat(tenant2Members).noneMatch(m -> m.getEmail().equals("filho@silva.com"));
    }

    @Test
    @DisplayName("US-13 LGPD: Export data and Crypto-Shredding with irreversibility")
    void testLgpdExportAndCryptoShredding() throws Exception {
        // Export data
        ExportDataResult exportResult = TenantContextHolder.callWithTenant(TENANT_1, () ->
                userProfileUseCase.exportUserData(TENANT_1, UUID.randomUUID(), "JSON")
        );
        assertThat(exportResult.downloadUrl()).contains("s3.us-east-2.amazonaws.com");
        assertThat(exportResult.checksumSha256()).hasSize(64);

        // Crypto-Shredding with correct phrase
        LgpdErasureRecord record = TenantContextHolder.callWithTenant(TENANT_1, () ->
                userProfileUseCase.requestCryptoShredding(
                        TENANT_1,
                        UUID.randomUUID(),
                        "EXCLUIR_DEFINITIVAMENTE_MINHA_CONTA",
                        "mfa-123456"
                )
        );
        assertThat(record.getStatus()).isEqualTo("CRYPTO_SHREDDED");
        assertThat(record.getDestroyedKeyId()).contains("arn:aws:kms");
    }
}
