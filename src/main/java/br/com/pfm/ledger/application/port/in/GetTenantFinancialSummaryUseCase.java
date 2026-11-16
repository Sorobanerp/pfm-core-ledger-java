package br.com.pfm.ledger.application.port.in;

import java.util.List;
import java.util.UUID;

/**
 * Inbound Use Case Port implementing the gRPC `GetTenantFinancialSummary` contract from `pfm-contracts`.
 */
public interface GetTenantFinancialSummaryUseCase {

    record CreditCardMatrixDto(
            String cardId,
            String cardName,
            String cardLastFour,
            String creditLimit,
            String availableLimit,
            String currentInvoiceAmount,
            String nextMonthInvoiceAmount,
            String futureCommittedAmount,
            int closingDay,
            int dueDay
    ) {
    }

    record FutureCommitmentBucketDto(
            String competenceMonth,
            String cardsInstallmentsAmount,
            String contractsAmount,
            String totalCommittedAmount,
            String incomeCommitmentPercentage
    ) {
    }

    record TenantFinancialSummaryOutput(
            String tenantId,
            String referenceMonth,
            String currency,
            String totalCurrentInvoice,
            String totalNextMonthInvoice,
            String totalFutureCommitment,
            String currentIncomeCommitmentPct,
            boolean overcommittedAlertTriggered,
            List<CreditCardMatrixDto> cardMatrix,
            List<FutureCommitmentBucketDto> futureCommitmentTimeline
    ) {
    }

    TenantFinancialSummaryOutput getTenantFinancialSummary(UUID tenantId, String referenceMonth, String currency);
}
