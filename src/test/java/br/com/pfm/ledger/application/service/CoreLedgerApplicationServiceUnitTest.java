package br.com.pfm.ledger.application.service;

import br.com.pfm.ledger.application.port.in.CommitCategorizedTransactionUseCase.CategorizedTransactionCommand;
import br.com.pfm.ledger.application.port.in.CommitCategorizedTransactionUseCase.CommitTransactionResult;
import br.com.pfm.ledger.application.port.out.LedgerEntryRepositoryPort;
import br.com.pfm.ledger.application.port.out.TransactionalOutboxPort;
import br.com.pfm.ledger.domain.DoubleEntryLedgerService;
import br.com.pfm.ledger.domain.InstallmentSplitter;
import br.com.pfm.ledger.domain.LedgerEntry;
import br.com.pfm.ledger.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CoreLedgerApplicationService Pure Unit Tests")
class CoreLedgerApplicationServiceUnitTest {

    @Mock
    private LedgerEntryRepositoryPort ledgerRepository;

    @Mock
    private TransactionalOutboxPort outboxPort;

    private CoreLedgerApplicationService service;

    @BeforeEach
    void setUp() {
        service = new CoreLedgerApplicationService(ledgerRepository, outboxPort);
    }

    @Test
    void shouldHandleNegativeAmountRefundWithSyntheticPurchase() {
        UUID tenantId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String idemp = "refund-synth-1";

        CategorizedTransactionCommand command = new CategorizedTransactionCommand(
                tenantId,
                accountId,
                null,
                idemp,
                "ALIMENTACAO",
                "MERCADO",
                new BigDecimal("-75.50"),
                "BRL",
                LocalDate.now(),
                "Estorno Parcial Mercado",
                1, 1, 10,
                null
        );

        when(ledgerRepository.findByIdempotencyKey(idemp)).thenReturn(Optional.empty());
        when(ledgerRepository.findAll()).thenReturn(Collections.emptyList());
        when(ledgerRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        CommitTransactionResult result = service.commitCategorizedTransaction(command);

        assertNotNull(result);
        assertFalse(result.idempotentReplay());
        assertEquals(LedgerEntry.EntryDirection.CREDIT, result.primaryEntry().direction());
        verify(ledgerRepository).save(any(LedgerEntry.class));
        verify(outboxPort).appendOutboxEvent(eq(tenantId), any(), any(), any(), any(Money.class), any(), any());
    }

    @Test
    void shouldHandleNegativeAmountRefundWithExistingPurchase() {
        UUID tenantId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID origId = UUID.randomUUID();
        String idemp = "refund-existing-1";

        LedgerEntry original = new LedgerEntry(
                origId, tenantId, accountId, null,
                "TRANSPORTE", "UBER",
                LedgerEntry.EntryDirection.DEBIT,
                LedgerEntry.EntryType.STANDARD_EXPENSE,
                Money.brl("100.00"),
                LocalDate.now(), LocalDate.now(), "Corrida Uber",
                "uber-orig-1", null, LedgerEntry.ReconciliationStatus.CONCILED, Instant.now()
        );

        CategorizedTransactionCommand command = new CategorizedTransactionCommand(
                tenantId,
                accountId,
                null,
                idemp,
                "TRANSPORTE",
                "UBER",
                new BigDecimal("-30.00"),
                "BRL",
                LocalDate.now(),
                "Estorno Uber",
                1, 1, 10,
                origId
        );

        when(ledgerRepository.findByIdempotencyKey(idemp)).thenReturn(Optional.empty());
        when(ledgerRepository.findById(origId)).thenReturn(Optional.of(original));
        when(ledgerRepository.findAll()).thenReturn(List.of(original));
        when(ledgerRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        CommitTransactionResult result = service.commitCategorizedTransaction(command);

        assertNotNull(result);
        assertFalse(result.idempotentReplay());
        assertEquals(LedgerEntry.EntryDirection.CREDIT, result.primaryEntry().direction());
        verify(ledgerRepository).save(any(LedgerEntry.class));
        verify(outboxPort).appendOutboxEvent(eq(tenantId), any(), any(), any(), any(Money.class), any(), any());
    }

    @Test
    void shouldHandleInstallmentSplitterWithSingleInstallment() {
        InstallmentSplitter splitter = new InstallmentSplitter();
        List<Money> amounts = splitter.computeInstallmentAmountsLastAdjusted(Money.brl("150.00"), 1);
        assertEquals(1, amounts.size());
        assertEquals(Money.brl("150.00"), amounts.get(0));
    }

    @Test
    void shouldCoverDoubleEntryPostingRecord() {
        UUID tenantId = UUID.randomUUID();
        UUID accId = UUID.randomUUID();
        LedgerEntry debit = new LedgerEntry(
                UUID.randomUUID(), tenantId, accId, null,
                "DESPESA", "SUB",
                LedgerEntry.EntryDirection.DEBIT,
                LedgerEntry.EntryType.STANDARD_EXPENSE,
                Money.brl("100.00"),
                LocalDate.now(), LocalDate.now(), "D", "id-d", null,
                LedgerEntry.ReconciliationStatus.CONCILED, Instant.now()
        );
        LedgerEntry credit = new LedgerEntry(
                UUID.randomUUID(), tenantId, accId, null,
                "DESPESA", "SUB",
                LedgerEntry.EntryDirection.CREDIT,
                LedgerEntry.EntryType.STANDARD_EXPENSE,
                Money.brl("100.00"),
                LocalDate.now(), LocalDate.now(), "C", "id-c", null,
                LedgerEntry.ReconciliationStatus.CONCILED, Instant.now()
        );

        DoubleEntryLedgerService.DoubleEntryPosting posting = new DoubleEntryLedgerService.DoubleEntryPosting(debit, credit, null, true);
        assertEquals(debit, posting.debitLeg());
        assertEquals(credit, posting.creditLeg());
        assertNotNull(posting.toString());
        assertEquals(posting, new DoubleEntryLedgerService.DoubleEntryPosting(debit, credit, null, true));

        assertThrows(IllegalArgumentException.class, () -> new DoubleEntryLedgerService.DoubleEntryPosting(credit, debit, null, true));
        assertThrows(IllegalArgumentException.class, () -> new DoubleEntryLedgerService.DoubleEntryPosting(debit, debit, null, true));
        LedgerEntry diffAmount = new LedgerEntry(
                UUID.randomUUID(), tenantId, accId, null,
                "DESPESA", "SUB",
                LedgerEntry.EntryDirection.CREDIT,
                LedgerEntry.EntryType.STANDARD_EXPENSE,
                Money.brl("200.00"),
                LocalDate.now(), LocalDate.now(), "C2", "id-c2", null,
                LedgerEntry.ReconciliationStatus.CONCILED, Instant.now()
        );
        assertThrows(IllegalArgumentException.class, () -> new DoubleEntryLedgerService.DoubleEntryPosting(debit, diffAmount, null, true));
    }
}
