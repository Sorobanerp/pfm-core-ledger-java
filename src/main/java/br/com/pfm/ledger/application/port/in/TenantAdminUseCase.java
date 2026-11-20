package br.com.pfm.ledger.application.port.in;

import br.com.pfm.ledger.domain.SubscriptionPlan;
import br.com.pfm.ledger.domain.SubscriptionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inbound Port for SaaS Platform Administration (US-12).
 * Exposes tenant governance, subscription management, and platform-level metrics.
 */
public interface TenantAdminUseCase {

    record TenantSummaryDTO(
            UUID tenantId,
            String name,
            SubscriptionPlan plan,
            SubscriptionStatus status,
            int maxMembers,
            int maxCards,
            int monthlyPdfQuota,
            int usedPdfQuota,
            Instant createdAt,
            Instant updatedAt
    ) {}

    record PlatformMetricsDTO(
            int totalTenants,
            int activeTenants,
            int trialTenants,
            int pastDueTenants,
            int suspendedTenants,
            long totalTransactionsProcessed,
            long totalAiTokensConsumed,
            long totalPdfStatementsImported,
            Instant generatedAt
    ) {}

    List<TenantSummaryDTO> listTenants(int pageSize, String pageToken, String statusFilter, String planFilter);

    TenantSummaryDTO changeTenantPlan(UUID tenantId, SubscriptionPlan newPlan);

    TenantSummaryDTO updateSubscriptionStatus(UUID tenantId, SubscriptionStatus newStatus, String reason);

    PlatformMetricsDTO getPlatformMetrics(String period);
}
