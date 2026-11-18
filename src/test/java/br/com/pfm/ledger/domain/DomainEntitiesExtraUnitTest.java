package br.com.pfm.ledger.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Domain Entities Extra Unit Tests for Deep Coverage")
class DomainEntitiesExtraUnitTest {

    @Test
    void shouldExerciseCreditCardValidationAndMethods() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID accId = UUID.randomUUID();

        CreditCard card = new CreditCard(
                id, tenantId, accId, "Itaú Black", "5285",
                Money.brl("50000.00"), Money.brl("45000.00"), 25, 5
        );

        assertEquals(id, card.id());
        assertEquals(tenantId, card.tenantId());
        assertEquals(accId, card.financialAccountId());
        assertEquals("Itaú Black", card.cardName());
        assertEquals("5285", card.cardLastFour());
        assertEquals(Money.brl("50000.00"), card.creditLimit());
        assertEquals(Money.brl("45000.00"), card.availableLimit());
        assertEquals(25, card.closingDay());
        assertEquals(5, card.dueDay());

        CreditCard updated = card.consumeLimit(Money.brl("5000.00"));
        assertEquals(Money.brl("40000.00"), updated.availableLimit());

        assertThrows(IllegalArgumentException.class, () -> new CreditCard(
                id, tenantId, accId, "Card", "1234", Money.brl("100"), Money.brl("100"), 0, 5
        ));
        assertThrows(IllegalArgumentException.class, () -> new CreditCard(
                id, tenantId, accId, "Card", "1234", Money.brl("100"), Money.brl("100"), 32, 5
        ));
        assertThrows(IllegalArgumentException.class, () -> new CreditCard(
                id, tenantId, accId, "Card", "1234", Money.brl("100"), Money.brl("100"), 25, 0
        ));
        assertThrows(IllegalArgumentException.class, () -> new CreditCard(
                id, tenantId, accId, "Card", "1234", Money.brl("100"), Money.brl("100"), 25, 32
        ));
    }

    @Test
    void shouldExerciseInstallmentSplitterValidation() {
        InstallmentSplitter splitter = new InstallmentSplitter();
        UUID tenantId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        YearMonth ym = YearMonth.of(2026, 4);

        assertThrows(IllegalArgumentException.class, () -> splitter.splitPurchase(
                tenantId, cardId, parentId, Money.zeroBrl(), 1, 10, ym, 10
        ));
        assertThrows(IllegalArgumentException.class, () -> splitter.splitPurchase(
                tenantId, cardId, parentId, Money.brl("-10.00"), 1, 10, ym, 10
        ));
        assertThrows(IllegalArgumentException.class, () -> splitter.splitPurchase(
                tenantId, cardId, parentId, Money.brl("100.00"), 1, 0, ym, 10
        ));
        assertThrows(IllegalArgumentException.class, () -> splitter.splitPurchase(
                tenantId, cardId, parentId, Money.brl("100.00"), 0, 5, ym, 10
        ));
        assertThrows(IllegalArgumentException.class, () -> splitter.splitPurchase(
                tenantId, cardId, parentId, Money.brl("100.00"), 6, 5, ym, 10
        ));
        assertThrows(IllegalArgumentException.class, () -> splitter.splitPurchase(
                tenantId, cardId, parentId, Money.brl("100.00"), 1, 5, ym, 0
        ));
        assertThrows(IllegalArgumentException.class, () -> splitter.splitPurchase(
                tenantId, cardId, parentId, Money.brl("100.00"), 1, 5, ym, 32
        ));
    }

    @Test
    void shouldExerciseInstallmentScheduleGetters() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        LocalDate due = LocalDate.of(2026, 5, 10);

        InstallmentSchedule schedule = new InstallmentSchedule(
                id, tenantId, cardId, parentId, 2, 10, "2026-05", due, Money.brl("100.00"),
                InstallmentSchedule.InstallmentStatus.SCHEDULED
        );

        assertEquals(id, schedule.id());
        assertEquals(tenantId, schedule.tenantId());
        assertEquals(cardId, schedule.creditCardId());
        assertEquals(parentId, schedule.parentLedgerEntryId());
        assertEquals(2, schedule.installmentNumber());
        assertEquals(10, schedule.totalInstallments());
        assertEquals("2026-05", schedule.competenceMonth());
        assertEquals(due, schedule.dueDate());
        assertEquals(Money.brl("100.00"), schedule.amount());
        assertEquals(InstallmentSchedule.InstallmentStatus.SCHEDULED, schedule.status());
    }

    @Test
    void shouldExerciseRefundHandlerValidation() {
        RefundHandler handler = new RefundHandler();
        UUID tenantId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        UUID accId = UUID.randomUUID();

        LedgerEntry debitEntry = new LedgerEntry(
                entryId, tenantId, accId, null,
                "ALIMENTACAO", "RESTAURANTE",
                LedgerEntry.EntryDirection.DEBIT,
                LedgerEntry.EntryType.STANDARD_EXPENSE,
                Money.brl("200.00"),
                LocalDate.now(), LocalDate.now(), "Compra Teste",
                "idemp-1", null, LedgerEntry.ReconciliationStatus.CONCILED, Instant.now()
        );

        assertThrows(IllegalArgumentException.class, () -> handler.processRefund(
                debitEntry, BigDecimal.ZERO, LocalDate.now(), "Estorno Zero", "ref-idemp-2", Money.brl("500.00")
        ));

        LedgerEntry incomeEntry = new LedgerEntry(
                entryId, tenantId, accId, null,
                "RECEITAS", "SALARIO",
                LedgerEntry.EntryDirection.CREDIT,
                LedgerEntry.EntryType.STANDARD_INCOME,
                Money.brl("2000.00"),
                LocalDate.now(), LocalDate.now(), "Salario",
                "idemp-inc", null, LedgerEntry.ReconciliationStatus.CONCILED, Instant.now()
        );

        assertThrows(IllegalArgumentException.class, () -> handler.processRefund(
                incomeEntry, new BigDecimal("100.00"), LocalDate.now(), "Estorno Receita", "ref-idemp-inc", Money.brl("2000.00")
        ));

        // Valid refund with negative raw amount
        var outcome = handler.processRefund(
                debitEntry, new BigDecimal("-50.00"), LocalDate.now(), "Estorno Parcial", "ref-idemp-3", Money.brl("200.00")
        );
        assertNotNull(outcome);
        assertFalse(outcome.countedAsIncome());
        assertEquals(Money.brl("150.00"), outcome.updatedCategoryNetSpend());
        assertEquals(LedgerEntry.EntryDirection.CREDIT, outcome.refundCreditEntry().direction());
        assertEquals(LedgerEntry.EntryType.REFUND_CREDIT, outcome.refundCreditEntry().entryType());
    }
}
