package br.com.pfm.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure Domain Service implementing RN04 / UC-03:
 * 1. Reconciles NFC-e fiscal receipts ({@code PENDING_RECONCILIATION}) against credit card statement entries
 *    by matching {@code card_id} + exact {@code amount_brl} + date window {@code ±3 days}, unifying status to
 *    {@code RECONCILED} without duplicating the expense and keeping {@code receipt_items} attached.
 * 2. Detects personal inflation per EAN barcode when unit price increases strictly {@code > 10%}
 *    compared to the tenant's most recent purchase of the same EAN.
 * Zero external framework imports.
 */
public final class ReceiptReconciliationEngine {

    public static final int MAX_DATE_WINDOW_DAYS = 3;
    public static final BigDecimal INFLATION_THRESHOLD_PCT = new BigDecimal("10.0000");

    public enum ReceiptLifecycleStatus {
        PENDING_RECONCILIATION,
        RECONCILED,
        STANDALONE_CASH_EXPENSE
    }

    public record ReceiptItem(
            UUID id,
            UUID tenantId,
            UUID ledgerEntryId,
            String accessKey,
            int itemSequence,
            String ean,
            String description,
            BigDecimal quantity,
            String unitOfMeasure,
            Money unitPrice,
            Money totalPrice,
            Instant issuedAt
    ) {
        public ReceiptItem withLedgerEntryId(UUID newLedgerEntryId) {
            return new ReceiptItem(
                    id,
                    tenantId,
                    newLedgerEntryId,
                    accessKey,
                    itemSequence,
                    ean,
                    description,
                    quantity,
                    unitOfMeasure,
                    unitPrice,
                    totalPrice,
                    issuedAt
            );
        }
    }

    public record HistoricalEanPrice(
            String ean,
            String description,
            Money unitPrice,
            LocalDate purchaseDate,
            String merchantName
    ) {
    }

    public record PriceInflationAlert(
            String ean,
            String description,
            Money previousUnitPrice,
            Money currentUnitPrice,
            BigDecimal inflationPercentage,
            LocalDate previousPurchaseDate,
            String previousMerchantName
    ) {
    }

    public record FiscalReceiptCandidate(
            UUID receiptId,
            UUID tenantId,
            UUID cardId,
            String accessKey,
            String merchantName,
            LocalDate issueDate,
            Money totalAmountBrl,
            ReceiptLifecycleStatus status,
            List<ReceiptItem> items
    ) {
    }

    public record ReconciliationResult(
            ReceiptLifecycleStatus status,
            Optional<LedgerEntry> reconciledLedgerEntry,
            List<ReceiptItem> linkedItems,
            List<PriceInflationAlert> inflationAlerts,
            boolean createdDuplicateLedgerExpense
    ) {
    }

    /**
     * Matches an NFC-e receipt ({@code PENDING_RECONCILIATION}) against candidate credit card statement entries
     * using {@code card_id} + exact {@code amount_brl} + {@code ±3 days} window, and evaluates EAN inflation (>10%).
     */
    public ReconciliationResult reconcileAndDetectInflation(
            FiscalReceiptCandidate receipt,
            List<LedgerEntry> candidateStatementEntries,
            Map<String, HistoricalEanPrice> latestPriceByEan
    ) {
        Objects.requireNonNull(receipt, "receipt must not be null");
        Objects.requireNonNull(candidateStatementEntries, "candidateStatementEntries must not be null");
        Objects.requireNonNull(latestPriceByEan, "latestPriceByEan must not be null");

        List<PriceInflationAlert> alerts = detectEanPriceInflation(receipt.items(), latestPriceByEan);

        Optional<LedgerEntry> matchedEntryOpt = candidateStatementEntries.stream()
                .filter(entry -> entry.tenantId().equals(receipt.tenantId()))
                .filter(entry -> Objects.equals(entry.creditCardId(), receipt.cardId()))
                .filter(entry -> entry.amount().equals(receipt.totalAmountBrl()))
                .filter(entry -> {
                    long daysDiff = Math.abs(ChronoUnit.DAYS.between(receipt.issueDate(), entry.competenceDate()));
                    return daysDiff <= MAX_DATE_WINDOW_DAYS;
                })
                .findFirst();

        if (matchedEntryOpt.isPresent()) {
            LedgerEntry matched = matchedEntryOpt.get();
            LedgerEntry unifiedEntry = new LedgerEntry(
                    matched.id(),
                    matched.tenantId(),
                    matched.accountId(),
                    matched.creditCardId(),
                    matched.categoryCode(),
                    matched.subcategoryCode(),
                    matched.direction(),
                    matched.entryType(),
                    matched.amount(),
                    matched.competenceDate(),
                    matched.cashDate(),
                    matched.description(),
                    matched.idempotencyKey(),
                    matched.originalTransactionId(),
                    LedgerEntry.ReconciliationStatus.CONCILED,
                    matched.createdAt()
            );

            List<ReceiptItem> linkedItems = receipt.items().stream()
                    .map(item -> item.withLedgerEntryId(unifiedEntry.id()))
                    .toList();

            return new ReconciliationResult(
                    ReceiptLifecycleStatus.RECONCILED,
                    Optional.of(unifiedEntry),
                    linkedItems,
                    alerts,
                    false
            );
        }

        return new ReconciliationResult(
                ReceiptLifecycleStatus.PENDING_RECONCILIATION,
                Optional.empty(),
                Collections.unmodifiableList(receipt.items()),
                alerts,
                false
        );
    }

    /**
     * Detects personal inflation per EAN: generates a {@link PriceInflationAlert} whenever
     * {@code (currentUnitPrice - previousUnitPrice) / previousUnitPrice * 100 > 10.0000%}.
     */
    public List<PriceInflationAlert> detectEanPriceInflation(
            List<ReceiptItem> currentItems,
            Map<String, HistoricalEanPrice> latestPriceByEan
    ) {
        Objects.requireNonNull(currentItems, "currentItems must not be null");
        Objects.requireNonNull(latestPriceByEan, "latestPriceByEan must not be null");

        List<PriceInflationAlert> alerts = new ArrayList<>();
        for (ReceiptItem item : currentItems) {
            if (item.ean() == null || item.ean().isBlank()) {
                continue;
            }
            HistoricalEanPrice baseline = latestPriceByEan.get(item.ean());
            if (baseline == null || !baseline.unitPrice().isPositive()) {
                continue;
            }
            BigDecimal currentPrice = item.unitPrice().amount();
            BigDecimal previousPrice = baseline.unitPrice().amount();
            if (currentPrice.compareTo(previousPrice) <= 0) {
                continue;
            }
            BigDecimal increasePct = currentPrice.subtract(previousPrice)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(previousPrice, 4, RoundingMode.HALF_EVEN);

            if (increasePct.compareTo(INFLATION_THRESHOLD_PCT) > 0) {
                alerts.add(new PriceInflationAlert(
                        item.ean(),
                        item.description(),
                        baseline.unitPrice(),
                        item.unitPrice(),
                        increasePct,
                        baseline.purchaseDate(),
                        baseline.merchantName()
                ));
            }
        }
        return Collections.unmodifiableList(alerts);
    }
}
