package br.com.pfm.ledger.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("US-12 & US-13 Domain Models Comprehensive Unit Tests")
class TenantSubscriptionAndProfileUnitTest {

    @Test
    @DisplayName("TenantSubscription: Default trial creation and plan upgrade changes quotas")
    void testTenantSubscriptionPlanAndQuotas() {
        UUID tenantId = UUID.randomUUID();
        TenantSubscription sub = TenantSubscription.createDefaultTrial(tenantId);

        assertEquals(SubscriptionPlan.FREE, sub.getPlan());
        assertEquals(SubscriptionStatus.TRIAL, sub.getStatus());
        assertEquals(1, sub.getMaxMembers());
        assertEquals(1, sub.getMaxCards());
        assertEquals(3, sub.getMonthlyPdfQuota());
        assertEquals(0, sub.getUsedPdfQuota());

        // Consume quotas
        sub.consumePdfQuota();
        assertEquals(1, sub.getUsedPdfQuota());
        sub.consumePdfQuota();
        sub.consumePdfQuota();
        assertEquals(3, sub.getUsedPdfQuota());

        // Quota exceeded
        assertThrows(IllegalStateException.class, sub::consumePdfQuota);

        // Upgrade to FAMILY_PLUS
        sub.changePlan(SubscriptionPlan.FAMILY_PLUS);
        assertEquals(SubscriptionPlan.FAMILY_PLUS, sub.getPlan());
        assertEquals(5, sub.getMaxMembers());
        assertEquals(20, sub.getMaxCards());
        assertEquals(100, sub.getMonthlyPdfQuota());

        // Now can consume more
        sub.consumePdfQuota();
        assertEquals(4, sub.getUsedPdfQuota());

        // Test suspension blocks ingestion
        sub.updateStatus(SubscriptionStatus.SUSPENDED);
        assertThrows(IllegalStateException.class, sub::consumePdfQuota);
    }

    @Test
    @DisplayName("CategoryBudgetRule: 80% threshold calculation and over budget checks")
    void testCategoryBudgetThresholdAlerts() {
        UUID tenantId = UUID.randomUUID();
        CategoryBudgetRule rule = new CategoryBudgetRule(
                UUID.randomUUID(),
                tenantId,
                "ALIMENTACAO",
                "Supermercado & Alimentação",
                Money.brl("1000.0000"),
                80,
                Instant.now(),
                Instant.now()
        );

        // Under 80% (R$ 799,00) -> Alert not triggered
        assertFalse(rule.isAlertTriggered(Money.brl("799.0000")));
        assertFalse(rule.isOverBudget(Money.brl("799.0000")));

        // At 80% (R$ 800,00) -> Alert triggered
        assertTrue(rule.isAlertTriggered(Money.brl("800.0000")));
        assertFalse(rule.isOverBudget(Money.brl("800.0000")));

        // At 100% (R$ 1000,00) -> Alert triggered, not over budget
        assertTrue(rule.isAlertTriggered(Money.brl("1000.0000")));
        assertFalse(rule.isOverBudget(Money.brl("1000.0000")));

        // At 100.01% (R$ 1000,01) -> Over budget
        assertTrue(rule.isAlertTriggered(Money.brl("1000.0100")));
        assertTrue(rule.isOverBudget(Money.brl("1000.0100")));
    }

    @Test
    @DisplayName("FamilyMemberProfile: Role updates, spending allowance and profile modification")
    void testFamilyMemberProfile() {
        UUID tenantId = UUID.randomUUID();
        FamilyMemberProfile profile = new FamilyMemberProfile(
                UUID.randomUUID(),
                tenantId,
                "membro@familia.com",
                "membro@familia.com",
                "Nome Inicial",
                FamilyRole.MEMBER,
                Money.brl("1500.0000"),
                "ACTIVE",
                null,
                null,
                Instant.now(),
                Instant.now()
        );

        assertEquals("Nome Inicial", profile.getFullName());
        assertEquals(FamilyRole.MEMBER, profile.getRole());
        assertEquals(new BigDecimal("1500.0000"), profile.getSpendingAllowance().amount());

        profile.updateProfile("Nome Atualizado");
        assertEquals("Nome Atualizado", profile.getFullName());

        profile.updateRole(FamilyRole.OWNER);
        assertEquals(FamilyRole.OWNER, profile.getRole());

        profile.updateSpendingAllowance(Money.brl("3000.0000"));
        assertEquals(new BigDecimal("3000.0000"), profile.getSpendingAllowance().amount());
    }

    @Test
    @DisplayName("LgpdErasureRecord: Confirmation phrase validation and Crypto-Shredding")
    void testLgpdCryptoShredding() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Invalid phrase throws exception
        assertThrows(IllegalArgumentException.class, () ->
                LgpdErasureRecord.initiate(tenantId, userId, "FRASE_ERRADA")
        );

        // Valid initiation
        LgpdErasureRecord record = LgpdErasureRecord.initiate(
                tenantId,
                userId,
                "EXCLUIR_DEFINITIVAMENTE_MINHA_CONTA"
        );
        assertEquals("PENDING", record.getStatus());
        assertNull(record.getDestroyedKeyId());
        assertNull(record.getShreddedAt());

        // Mark shredded
        record.markShredded("arn:aws:kms:us-east-2:366476833548:key/destroyed-key-01");
        assertEquals("CRYPTO_SHREDDED", record.getStatus());
        assertEquals("arn:aws:kms:us-east-2:366476833548:key/destroyed-key-01", record.getDestroyedKeyId());
        assertNotNull(record.getShreddedAt());
    }
}
