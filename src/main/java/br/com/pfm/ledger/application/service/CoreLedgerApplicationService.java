package br.com.pfm.ledger.application.service;

import br.com.pfm.ledger.application.port.in.CommitCategorizedTransactionUseCase;
import br.com.pfm.ledger.application.port.in.GetTenantFinancialSummaryUseCase;
import br.com.pfm.ledger.application.port.in.ProcessReceiptUseCase;
import br.com.pfm.ledger.application.port.out.LedgerEntryRepositoryPort;
import br.com.pfm.ledger.application.port.out.TransactionalOutboxPort;
import br.com.pfm.ledger.domain.CreditCard;
import br.com.pfm.ledger.domain.DoubleEntryLedgerService;
import br.com.pfm.ledger.domain.InstallmentSchedule;
import br.com.pfm.ledger.domain.InstallmentSplitter;
import br.com.pfm.ledger.domain.LedgerEntry;
import br.com.pfm.ledger.domain.Money;
import br.com.pfm.ledger.domain.ReceiptReconciliationEngine;
import br.com.pfm.ledger.domain.RefundHandler;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application Service implementing US-07 and US-08 use cases:
 * - {@link CommitCategorizedTransactionUseCase}
 * - {@link ProcessReceiptUseCase}
 * - {@link GetTenantFinancialSummaryUseCase}
 */
@Service
public class CoreLedgerApplicationService implements
        CommitCategorizedTransactionUseCase,
        ProcessReceiptUseCase,
        GetTenantFinancialSummaryUseCase {

    private final LedgerEntryRepositoryPort ledgerRepository;
    private final TransactionalOutboxPort outboxPort;
    private final DoubleEntryLedgerService doubleEntryService;
    private final InstallmentSplitter installmentSplitter;
    private final RefundHandler refundHandler;
    private final ReceiptReconciliationEngine reconciliationEngine;

    public CoreLedgerApplicationService(
            LedgerEntryRepositoryPort ledgerRepository,
            TransactionalOutboxPort outboxPort
    ) {
        this.ledgerRepository = Objects.requireNonNull(ledgerRepository, "ledgerRepository must not be null");
        this.outboxPort = Objects.requireNonNull(outboxPort, "outboxPort must not be null");
        this.doubleEntryService = new DoubleEntryLedgerService();
        this.installmentSplitter = new InstallmentSplitter();
        this.refundHandler = new RefundHandler();
        this.reconciliationEngine = new ReceiptReconciliationEngine();
    }

    @Override
    @Transactional
    public CommitTransactionResult commitCategorizedTransaction(CategorizedTransactionCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        // 1. Idempotency verification by idempotency_key
        Optional<LedgerEntry> existing = ledgerRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            List<LedgerEntry> allEntries = ledgerRepository.findAll();
            Money netSpend = doubleEntryService.calculateNetCategoryExpense(allEntries, command.categoryCode());
            return new CommitTransactionResult(existing.get(), List.of(), true, netSpend);
        }

        String currency = command.currency() != null && !command.currency().isBlank()
                ? command.currency()
                : Money.DEFAULT_CURRENCY;

        LedgerEntry primarySaved;
        List<InstallmentSchedule> generatedSchedules = List.of();

        // 2. Check if REFUND (negative amount or explicit originalPurchaseEntryId with negative/refund)
        if (command.amount().compareTo(BigDecimal.ZERO) < 0) {
            LedgerEntry originalPurchase = command.originalPurchaseEntryId() != null
                    ? ledgerRepository.findById(command.originalPurchaseEntryId()).orElseGet(() -> buildSyntheticPurchase(command, currency))
                    : buildSyntheticPurchase(command, currency);

            List<LedgerEntry> beforeEntries = ledgerRepository.findAll();
            Money spendBefore = doubleEntryService.calculateNetCategoryExpense(beforeEntries, originalPurchase.categoryCode());

            RefundHandler.RefundOutcome outcome = refundHandler.processRefund(
                    originalPurchase,
                    command.amount(),
                    command.competenceDate(),
                    command.description(),
                    command.idempotencyKey(),
                    spendBefore
            );
            primarySaved = ledgerRepository.save(outcome.refundCreditEntry());
        } else {
            // Standard or Installment Purchase
            Money positiveAmount = Money.of(command.amount(), currency);
            boolean isInstallment = command.installmentTotal() > 1;
            LedgerEntry.EntryType entryType = isInstallment
                    ? LedgerEntry.EntryType.INSTALLMENT_ACCRUAL
                    : LedgerEntry.EntryType.STANDARD_EXPENSE;

            LedgerEntry expenseEntry = new LedgerEntry(
                    UUID.randomUUID(),
                    command.tenantId(),
                    command.accountId(),
                    command.creditCardId(),
                    command.categoryCode(),
                    command.subcategoryCode(),
                    LedgerEntry.EntryDirection.DEBIT,
                    entryType,
                    positiveAmount,
                    command.competenceDate(),
                    command.creditCardId() == null ? command.competenceDate() : null,
                    command.description(),
                    command.idempotencyKey(),
                    null,
                    LedgerEntry.ReconciliationStatus.PENDING,
                    Instant.now()
            );
            primarySaved = ledgerRepository.save(expenseEntry);

            if (isInstallment && command.creditCardId() != null) {
                YearMonth competenceYm = YearMonth.from(command.competenceDate());
                int dueDay = command.cardDueDay() >= 1 && command.cardDueDay() <= 31 ? command.cardDueDay() : 10;
                generatedSchedules = installmentSplitter.splitPurchase(
                        command.tenantId(),
                        command.creditCardId(),
                        primarySaved.id(),
                        positiveAmount,
                        command.installmentCurrent(),
                        command.installmentTotal(),
                        competenceYm,
                        dueDay
                );
                ledgerRepository.saveInstallmentSchedules(generatedSchedules);
            }
        }

        // 3. Append LedgerEntryCommittedEvent to outbox_events in the same ACID transaction
        String eventPayloadJson = """
                {"tenant_id":"%s","idempotency_key":"%s","journal_entry_id":"%s","source_reference_id":"%s","posting_date":"%s","total_amount":"%s","currency":"%s","category_code":"%s","subcategory_code":"%s","direction":"%s"}"""
                .formatted(
                        primarySaved.tenantId(),
                        primarySaved.idempotencyKey(),
                        primarySaved.id(),
                        primarySaved.originalTransactionId() != null ? primarySaved.originalTransactionId() : primarySaved.id(),
                        primarySaved.competenceDate(),
                        primarySaved.amount().toCanonicalString(),
                        primarySaved.amount().currency(),
                        primarySaved.categoryCode(),
                        primarySaved.subcategoryCode(),
                        primarySaved.direction().name()
                );

        outboxPort.appendOutboxEvent(
                primarySaved.tenantId(),
                "LEDGER_ENTRY",
                primarySaved.id(),
                "LedgerEntryCommittedEvent",
                primarySaved.amount(),
                primarySaved.idempotencyKey(),
                eventPayloadJson
        );

        List<LedgerEntry> updatedEntries = ledgerRepository.findAll();
        Money resultingSpend = doubleEntryService.calculateNetCategoryExpense(updatedEntries, primarySaved.categoryCode());

        return new CommitTransactionResult(primarySaved, generatedSchedules, false, resultingSpend);
    }

    @Override
    @Transactional
    public ProcessReceiptOutput processReceipt(ProcessReceiptCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String currency = command.currency() != null && !command.currency().isBlank()
                ? command.currency()
                : Money.DEFAULT_CURRENCY;

        UUID receiptId = UUID.randomUUID();
        LocalDate issueDate = command.issuedAt().atZone(ZoneOffset.UTC).toLocalDate();
        Money totalAmountBrl = Money.of(command.totalAmount(), currency);

        List<ReceiptReconciliationEngine.ReceiptItem> domainItems = new ArrayList<>();
        List<String> eans = new ArrayList<>();
        for (ReceiptLineItemDto dto : command.items()) {
            if (dto.ean() != null && !dto.ean().isBlank()) {
                eans.add(dto.ean());
            }
            domainItems.add(new ReceiptReconciliationEngine.ReceiptItem(
                    UUID.randomUUID(),
                    command.tenantId(),
                    null,
                    command.accessKey(),
                    dto.itemSequence(),
                    dto.ean(),
                    dto.description(),
                    new BigDecimal(dto.quantity()),
                    dto.unitOfMeasure(),
                    Money.of(dto.unitPrice(), currency),
                    Money.of(dto.totalPrice(), currency),
                    command.issuedAt()
            ));
        }

        // Query historical EAN prices BEFORE inserting current receipt items
        Map<String, ReceiptReconciliationEngine.HistoricalEanPrice> latestPricesByEan =
                ledgerRepository.findLatestPricesByEan(eans);

        // Query statement entries within ±3 days window for reconciliation
        List<LedgerEntry> candidateEntries = ledgerRepository.findByCompetenceDateBetween(
                issueDate.minusDays(ReceiptReconciliationEngine.MAX_DATE_WINDOW_DAYS),
                issueDate.plusDays(ReceiptReconciliationEngine.MAX_DATE_WINDOW_DAYS)
        );

        ReceiptReconciliationEngine.FiscalReceiptCandidate candidate =
                new ReceiptReconciliationEngine.FiscalReceiptCandidate(
                        receiptId,
                        command.tenantId(),
                        command.cardId(),
                        command.accessKey(),
                        command.merchantName(),
                        issueDate,
                        totalAmountBrl,
                        ReceiptReconciliationEngine.ReceiptLifecycleStatus.PENDING_RECONCILIATION,
                        domainItems
                );

        ReceiptReconciliationEngine.ReconciliationResult result =
                reconciliationEngine.reconcileAndDetectInflation(candidate, candidateEntries, latestPricesByEan);

        UUID matchedTxId = null;
        UUID journalEntryId = null;
        String statusStr = "PENDING_STATEMENT";
        String confidence = "0.0000";

        if (result.reconciledLedgerEntry().isPresent()) {
            LedgerEntry matched = result.reconciledLedgerEntry().get();
            ledgerRepository.updateReconciliationStatus(matched.id(), LedgerEntry.ReconciliationStatus.CONCILED);
            ledgerRepository.saveReceiptItems(result.linkedItems());
            matchedTxId = matched.id();
            journalEntryId = matched.id();
            statusStr = "MATCHED_STATEMENT_LINE";
            confidence = "1.0000";
        } else {
            ledgerRepository.saveReceiptItems(result.linkedItems());
        }

        return new ProcessReceiptOutput(
                receiptId,
                statusStr,
                matchedTxId,
                journalEntryId,
                confidence,
                !result.inflationAlerts().isEmpty(),
                result.inflationAlerts()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public TenantFinancialSummaryOutput getTenantFinancialSummary(
            UUID tenantId,
            String referenceMonth,
            String currency
    ) {
        String curr = currency != null && !currency.isBlank() ? currency : Money.DEFAULT_CURRENCY;
        YearMonth refYm = YearMonth.parse(referenceMonth);
        YearMonth nextYm = refYm.plusMonths(1);

        List<CreditCard> cards = ledgerRepository.findAllCreditCards();
        List<InstallmentSchedule> allSchedules = ledgerRepository.findAllInstallmentSchedules();
        List<LedgerEntry> currentMonthEntries = ledgerRepository.findByCompetenceDateBetween(
                refYm.atDay(1),
                refYm.atEndOfMonth()
        );
        Money monthlyNetIncome = ledgerRepository.findTenantMonthlyNetIncome(tenantId);

        Money totalCurrentInvoice = Money.zero(curr);
        Money totalNextMonthInvoice = Money.zero(curr);
        Money totalFutureCommitment = Money.zero(curr);

        List<CreditCardMatrixDto> cardMatrix = new ArrayList<>();
        for (CreditCard card : cards) {
            Money cardCurrent = currentMonthEntries.stream()
                    .filter(e -> card.id().equals(e.creditCardId()))
                    .filter(e -> e.direction() == LedgerEntry.EntryDirection.DEBIT)
                    .filter(e -> e.entryType() != LedgerEntry.EntryType.INVOICE_CASH_SETTLEMENT)
                    .map(LedgerEntry::amount)
                    .reduce(Money.zero(curr), Money::add);

            Money cardNext = allSchedules.stream()
                    .filter(s -> card.id().equals(s.creditCardId()))
                    .filter(s -> nextYm.toString().equals(s.competenceMonth()))
                    .map(InstallmentSchedule::amount)
                    .reduce(Money.zero(curr), Money::add);

            Money cardFuture = allSchedules.stream()
                    .filter(s -> card.id().equals(s.creditCardId()))
                    .filter(s -> YearMonth.parse(s.competenceMonth()).isAfter(refYm))
                    .map(InstallmentSchedule::amount)
                    .reduce(Money.zero(curr), Money::add);

            totalCurrentInvoice = totalCurrentInvoice.add(cardCurrent);
            totalNextMonthInvoice = totalNextMonthInvoice.add(cardNext);
            totalFutureCommitment = totalFutureCommitment.add(cardFuture);

            cardMatrix.add(new CreditCardMatrixDto(
                    card.id().toString(),
                    card.cardName(),
                    card.cardLastFour(),
                    card.creditLimit().toCanonicalString(),
                    card.availableLimit().toCanonicalString(),
                    cardCurrent.toCanonicalString(),
                    cardNext.toCanonicalString(),
                    cardFuture.toCanonicalString(),
                    card.closingDay(),
                    card.dueDay()
            ));
        }

        List<FutureCommitmentBucketDto> timeline = new ArrayList<>();
        for (int offset = 1; offset <= 12; offset++) {
            YearMonth bucketYm = refYm.plusMonths(offset);
            Money bucketInstallments = allSchedules.stream()
                    .filter(s -> bucketYm.toString().equals(s.competenceMonth()))
                    .map(InstallmentSchedule::amount)
                    .reduce(Money.zero(curr), Money::add);
            BigDecimal pct = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_EVEN);
            if (monthlyNetIncome.isPositive()) {
                pct = bucketInstallments.amount()
                        .multiply(BigDecimal.valueOf(100))
                        .divide(monthlyNetIncome.amount(), 4, RoundingMode.HALF_EVEN);
            }
            timeline.add(new FutureCommitmentBucketDto(
                    bucketYm.toString(),
                    bucketInstallments.toCanonicalString(),
                    Money.zero(curr).toCanonicalString(),
                    bucketInstallments.toCanonicalString(),
                    pct.toPlainString()
            ));
        }

        DoubleEntryLedgerService.FutureCommitmentEvaluation eval = doubleEntryService.evaluateFutureCommitment(
                tenantId,
                totalCurrentInvoice,
                Money.zero(curr),
                totalFutureCommitment,
                monthlyNetIncome
        );

        return new TenantFinancialSummaryOutput(
                tenantId.toString(),
                referenceMonth,
                curr,
                totalCurrentInvoice.toCanonicalString(),
                totalNextMonthInvoice.toCanonicalString(),
                totalFutureCommitment.toCanonicalString(),
                eval.commitmentPercentage().toPlainString(),
                eval.overcommitted(),
                cardMatrix,
                timeline
        );
    }

    private LedgerEntry buildSyntheticPurchase(CategorizedTransactionCommand command, String currency) {
        return new LedgerEntry(
                command.originalPurchaseEntryId() != null ? command.originalPurchaseEntryId() : UUID.randomUUID(),
                command.tenantId(),
                command.accountId(),
                command.creditCardId(),
                command.categoryCode(),
                command.subcategoryCode(),
                LedgerEntry.EntryDirection.DEBIT,
                LedgerEntry.EntryType.STANDARD_EXPENSE,
                Money.of(command.amount().abs(), currency),
                command.competenceDate(),
                null,
                command.description(),
                command.idempotencyKey() + ":SYNTHETIC_ORIGIN",
                null,
                LedgerEntry.ReconciliationStatus.CONCILED,
                Instant.now()
        );
    }
}
