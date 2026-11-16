package br.com.pfm.ledger.application.port.in;

import br.com.pfm.ledger.domain.ReceiptReconciliationEngine;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inbound Use Case Port implementing the gRPC `ProcessReceipt` contract from `pfm-contracts`.
 */
public interface ProcessReceiptUseCase {

    record ReceiptLineItemDto(
            int itemSequence,
            String ean,
            String description,
            String quantity,
            String unitOfMeasure,
            String unitPrice,
            String totalPrice
    ) {
    }

    record ProcessReceiptCommand(
            UUID tenantId,
            String idempotencyKey,
            String accessKey,
            String merchantTaxId,
            String merchantName,
            Instant issuedAt,
            String totalAmount,
            String currency,
            UUID cardId,
            UUID fallbackAccountId,
            List<ReceiptLineItemDto> items
    ) {
    }

    record ProcessReceiptOutput(
            UUID receiptId,
            String reconciliationStatus,
            UUID matchedTransactionId,
            UUID journalEntryId,
            String matchConfidence,
            boolean hasInflationAlerts,
            List<ReceiptReconciliationEngine.PriceInflationAlert> inflationAlerts
    ) {
    }

    ProcessReceiptOutput processReceipt(ProcessReceiptCommand command);
}
