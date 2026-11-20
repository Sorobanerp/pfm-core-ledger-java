package br.com.pfm.ledger.application.port.out;

import br.com.pfm.ledger.application.port.in.TenantAdminUseCase.PlatformMetricsDTO;
import br.com.pfm.ledger.application.port.in.TenantAdminUseCase.TenantSummaryDTO;
import br.com.pfm.ledger.domain.SubscriptionPlan;
import br.com.pfm.ledger.domain.SubscriptionStatus;
import br.com.pfm.ledger.domain.TenantSubscription;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound Port for Tenant Subscription Persistence and Governance Queries (US-12).
 */
public interface TenantSubscriptionRepositoryPort {

    Optional<TenantSubscription> findByTenantId(UUID tenantId);

    TenantSubscription saveSubscription(TenantSubscription subscription);

    List<TenantSummaryDTO> listTenantsForAdmin(int pageSize, String pageToken, String statusFilter, String planFilter);

    Optional<TenantSummaryDTO> findTenantSummary(UUID tenantId);

    void updateTenantPlan(UUID tenantId, SubscriptionPlan plan);

    void updateSubscriptionStatus(UUID tenantId, SubscriptionStatus status, String reason);

    PlatformMetricsDTO calculatePlatformMetrics();
}
