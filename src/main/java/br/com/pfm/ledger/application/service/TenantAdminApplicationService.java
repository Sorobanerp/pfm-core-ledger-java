package br.com.pfm.ledger.application.service;

import br.com.pfm.ledger.application.port.in.TenantAdminUseCase;
import br.com.pfm.ledger.application.port.out.TenantSubscriptionRepositoryPort;
import br.com.pfm.ledger.domain.SubscriptionPlan;
import br.com.pfm.ledger.domain.SubscriptionStatus;
import br.com.pfm.ledger.domain.TenantSubscription;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application Service orchestrating SaaS Tenant Administration & Subscription Lifecycle (US-12).
 */
@Service
public class TenantAdminApplicationService implements TenantAdminUseCase {

    private final TenantSubscriptionRepositoryPort subscriptionRepository;

    public TenantAdminApplicationService(TenantSubscriptionRepositoryPort subscriptionRepository) {
        this.subscriptionRepository = Objects.requireNonNull(subscriptionRepository, "subscriptionRepository must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantSummaryDTO> listTenants(int pageSize, String pageToken, String statusFilter, String planFilter) {
        return subscriptionRepository.listTenantsForAdmin(pageSize, pageToken, statusFilter, planFilter);
    }

    @Override
    @Transactional
    public TenantSummaryDTO changeTenantPlan(UUID tenantId, SubscriptionPlan newPlan) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(newPlan, "newPlan must not be null");

        TenantSubscription subscription = subscriptionRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantSubscription.createDefaultTrial(tenantId));

        subscription.changePlan(newPlan);
        subscriptionRepository.saveSubscription(subscription);
        subscriptionRepository.updateTenantPlan(tenantId, newPlan);

        return subscriptionRepository.findTenantSummary(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found after plan update: " + tenantId));
    }

    @Override
    @Transactional
    public TenantSummaryDTO updateSubscriptionStatus(UUID tenantId, SubscriptionStatus newStatus, String reason) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");

        TenantSubscription subscription = subscriptionRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantSubscription.createDefaultTrial(tenantId));

        subscription.updateStatus(newStatus);
        subscriptionRepository.saveSubscription(subscription);
        subscriptionRepository.updateSubscriptionStatus(tenantId, newStatus, reason);

        return subscriptionRepository.findTenantSummary(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found after status update: " + tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public PlatformMetricsDTO getPlatformMetrics(String period) {
        return subscriptionRepository.calculatePlatformMetrics();
    }
}
