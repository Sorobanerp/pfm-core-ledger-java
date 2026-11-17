package br.com.pfm.ledger.application.port.out;

import br.com.pfm.ledger.domain.CreditCard;
import br.com.pfm.ledger.domain.InstallmentSchedule;
import br.com.pfm.ledger.domain.LedgerEntry;
import br.com.pfm.ledger.domain.Money;
import br.com.pfm.ledger.domain.ReceiptReconciliationEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Port Default Methods Unit Tests")
class PortDefaultMethodsTest {

    @Test
    void shouldExerciseLedgerEntryRepositoryPortDefaultMethods() {
        LedgerEntryRepositoryPort dummyRepo = new LedgerEntryRepositoryPort() {
            @Override
            public LedgerEntry save(LedgerEntry entry) {
                return entry;
            }

            @Override
            public Optional<LedgerEntry> findById(UUID id) {
                return Optional.empty();
            }

            @Override
            public List<LedgerEntry> findAll() {
                return List.of();
            }

            @Override
            public List<LedgerEntry> findByCompetenceDateBetween(LocalDate startInclusive, LocalDate endInclusive) {
                return List.of();
            }
        };

        assertTrue(dummyRepo.findByIdempotencyKey("key-1").isEmpty());
        assertThrows(UnsupportedOperationException.class, () ->
                dummyRepo.updateReconciliationStatus(UUID.randomUUID(), LedgerEntry.ReconciliationStatus.CONCILED)
        );

        assertDoesNotThrow(() -> dummyRepo.saveInstallmentSchedules(List.of()));
        assertTrue(dummyRepo.findInstallmentSchedulesByMonth("2026-03").isEmpty());
        assertTrue(dummyRepo.findAllInstallmentSchedules().isEmpty());

        assertDoesNotThrow(() -> dummyRepo.saveReceiptItems(List.of()));
        assertTrue(dummyRepo.findLatestPricesByEan(List.of("1234567890123")).isEmpty());

        assertTrue(dummyRepo.findAllCreditCards().isEmpty());
        assertTrue(dummyRepo.findCreditCardById(UUID.randomUUID()).isEmpty());
        assertEquals(Money.zeroBrl(), dummyRepo.findTenantMonthlyNetIncome(UUID.randomUUID()));
    }

    @Test
    void shouldExerciseTransactionalOutboxPortDefaultMethods() {
        class MockOutbox implements TransactionalOutboxPort {
            UUID capturedTenant;
            Money capturedMoney;

            @Override
            public void appendOutboxEvent(UUID tenantId, String aggregateType, UUID aggregateId, String eventType, String idempotencyKey, String payloadJson) {
                this.capturedTenant = tenantId;
            }

            @Override
            public void appendOutboxEvent(UUID tenantId, String aggregateType, UUID aggregateId, String eventType, Money monetaryAmount, String idempotencyKey, String payloadJson) {
                this.capturedMoney = monetaryAmount;
                TransactionalOutboxPort.super.appendOutboxEvent(tenantId, aggregateType, aggregateId, eventType, monetaryAmount, idempotencyKey, payloadJson);
            }
        }

        MockOutbox mock = new MockOutbox();
        UUID tenant = UUID.randomUUID();
        UUID aggId = UUID.randomUUID();
        Money amount = Money.brl("150.00");

        mock.appendOutboxEvent(tenant, "LedgerEntry", aggId, "LEDGER_COMMITTED", amount, "idemp-1", "{}");

        assertEquals(tenant, mock.capturedTenant);
        assertEquals(amount, mock.capturedMoney);
        assertTrue(mock.findPendingEvents(10).isEmpty());
        assertDoesNotThrow(() -> mock.markEventPublished(UUID.randomUUID(), Instant.now()));

        TransactionalOutboxPort.OutboxEventRecord record = new TransactionalOutboxPort.OutboxEventRecord(
                UUID.randomUUID(), tenant, "LedgerEntry", aggId, "LEDGER_COMMITTED", amount, "key-1", "{}", "PUBLISHED", Instant.now(), Instant.now()
        );
        assertEquals(tenant, record.tenantId());
        assertEquals("PUBLISHED", record.status());
    }
}
