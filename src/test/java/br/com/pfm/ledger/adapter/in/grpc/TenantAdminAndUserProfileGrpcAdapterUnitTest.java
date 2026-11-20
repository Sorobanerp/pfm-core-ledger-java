package br.com.pfm.ledger.adapter.in.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.pfm.ledger.adapter.in.grpc.TenantAdminGrpcServerAdapter.ChangeTenantPlanCommand;
import br.com.pfm.ledger.adapter.in.grpc.TenantAdminGrpcServerAdapter.GetPlatformMetricsCommand;
import br.com.pfm.ledger.adapter.in.grpc.TenantAdminGrpcServerAdapter.ListTenantsRequestCommand;
import br.com.pfm.ledger.adapter.in.grpc.TenantAdminGrpcServerAdapter.UpdateSubscriptionStatusCommand;
import br.com.pfm.ledger.adapter.in.grpc.UserProfileGrpcServerAdapter.ExportUserDataCommand;
import br.com.pfm.ledger.adapter.in.grpc.UserProfileGrpcServerAdapter.GetCategoryBudgetsCommand;
import br.com.pfm.ledger.adapter.in.grpc.UserProfileGrpcServerAdapter.GetUserProfileCommand;
import br.com.pfm.ledger.adapter.in.grpc.UserProfileGrpcServerAdapter.InviteFamilyMemberCommand;
import br.com.pfm.ledger.adapter.in.grpc.UserProfileGrpcServerAdapter.ListFamilyMembersCommand;
import br.com.pfm.ledger.adapter.in.grpc.UserProfileGrpcServerAdapter.RemoveFamilyMemberCommand;
import br.com.pfm.ledger.adapter.in.grpc.UserProfileGrpcServerAdapter.RequestCryptoShreddingCommand;
import br.com.pfm.ledger.adapter.in.grpc.UserProfileGrpcServerAdapter.SetCardPdfPasswordCommand;
import br.com.pfm.ledger.adapter.in.grpc.UserProfileGrpcServerAdapter.SetCategoryBudgetCommand;
import br.com.pfm.ledger.adapter.in.grpc.UserProfileGrpcServerAdapter.UpdateUserProfileCommand;
import br.com.pfm.ledger.application.port.in.TenantAdminUseCase;
import br.com.pfm.ledger.application.port.in.TenantAdminUseCase.PlatformMetricsDTO;
import br.com.pfm.ledger.application.port.in.TenantAdminUseCase.TenantSummaryDTO;
import br.com.pfm.ledger.application.port.in.UserProfileUseCase;
import br.com.pfm.ledger.application.port.in.UserProfileUseCase.ExportDataResult;
import br.com.pfm.ledger.domain.CategoryBudgetRule;
import br.com.pfm.ledger.domain.FamilyMemberProfile;
import br.com.pfm.ledger.domain.FamilyRole;
import br.com.pfm.ledger.domain.LgpdErasureRecord;
import br.com.pfm.ledger.domain.Money;
import br.com.pfm.ledger.domain.SubscriptionPlan;
import br.com.pfm.ledger.domain.SubscriptionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantAdminAndUserProfileGrpcAdapterUnitTest {

    @Test
    void shouldDelegateAllTenantAdminRpcEndpoints() throws Exception {
        TenantAdminUseCase useCase = mock(TenantAdminUseCase.class);
        TenantAdminGrpcServerAdapter adapter = new TenantAdminGrpcServerAdapter(useCase);

        UUID tenantId = UUID.randomUUID();
        TenantSummaryDTO summary = new TenantSummaryDTO(
                tenantId, "Família", SubscriptionPlan.FAMILY_PLUS, SubscriptionStatus.ACTIVE, 5, 1, 999, 0, Instant.now(), Instant.now()
        );
        when(useCase.listTenants(anyInt(), any(), any(), any())).thenReturn(List.of(summary));
        when(useCase.changeTenantPlan(any(), any())).thenReturn(summary);
        when(useCase.updateSubscriptionStatus(any(), any(), any())).thenReturn(summary);
        when(useCase.getPlatformMetrics(any())).thenReturn(new PlatformMetricsDTO(10, 8, 1, 1, 0, 1000L, 120L, 12L, Instant.now()));

        assertEquals(1, adapter.listTenants(new ListTenantsRequestCommand(10, "", "", "")).totalCount());
        assertEquals(1, adapter.listTenants(null).totalCount());
        assertEquals(tenantId, adapter.changeTenantPlan(new ChangeTenantPlanCommand(tenantId, "FAMILY_PLUS")).tenantId());
        assertEquals(tenantId, adapter.updateSubscriptionStatus(new UpdateSubscriptionStatusCommand(tenantId, "ACTIVE", "ok")).tenantId());
        assertEquals(10, adapter.getPlatformMetrics(new GetPlatformMetricsCommand("30d")).totalTenants());
        assertEquals(10, adapter.getPlatformMetrics(null).totalTenants());
    }

    @Test
    void shouldDelegateAllUserProfileRpcEndpoints() throws Exception {
        UserProfileUseCase useCase = mock(UserProfileUseCase.class);
        UserProfileGrpcServerAdapter adapter = new UserProfileGrpcServerAdapter(useCase);

        UUID tenantId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        FamilyMemberProfile profile = new FamilyMemberProfile(
                memberId, tenantId, "sub-1", "a@b.com", "Ana", FamilyRole.OWNER,
                Money.of("1000.0000", "BRL"), "ACTIVE", null, null, Instant.now(), Instant.now()
        );
        CategoryBudgetRule budget = new CategoryBudgetRule(
                UUID.randomUUID(), tenantId, "ALIMENTACAO", "Alimentação",
                Money.of("1500.0000", "BRL"), 80, Instant.now(), Instant.now()
        );
        ExportDataResult export = new ExportDataResult(tenantId, "exp-1", "https://s3/exp-1.json", Instant.now(), "abc");
        LgpdErasureRecord erasure = LgpdErasureRecord.initiate(tenantId, memberId, "EXCLUIR_DEFINITIVAMENTE_MINHA_CONTA");

        when(useCase.getUserProfile(any(), anyString())).thenReturn(profile);
        when(useCase.updateUserProfile(any(), anyString(), anyString())).thenReturn(profile);
        when(useCase.listFamilyMembers(any())).thenReturn(List.of(profile));
        when(useCase.inviteFamilyMember(any(), anyString(), anyString(), any(), any())).thenReturn(profile);
        when(useCase.removeFamilyMember(any(), any())).thenReturn(true);
        when(useCase.getCategoryBudgets(any())).thenReturn(List.of(budget));
        when(useCase.setCategoryBudget(any(), anyString(), anyString(), any(), anyInt())).thenReturn(budget);
        when(useCase.setCardPdfPassword(any(), any(), anyString())).thenReturn(true);
        when(useCase.exportUserData(any(), any(), anyString())).thenReturn(export);
        when(useCase.requestCryptoShredding(any(), any(), anyString(), anyString())).thenReturn(erasure);

        assertEquals(memberId, adapter.getUserProfile(new GetUserProfileCommand(tenantId, "sub-1")).getId());
        assertEquals(memberId, adapter.updateUserProfile(new UpdateUserProfileCommand(tenantId, "sub-1", "Ana Silva")).getId());
        assertEquals(1, adapter.listFamilyMembers(new ListFamilyMembersCommand(tenantId)).members().size());
        assertEquals(memberId, adapter.inviteFamilyMember(new InviteFamilyMemberCommand(tenantId, "m@b.com", "M", "MEMBER", "500.0000")).getId());
        assertTrue(adapter.removeFamilyMember(new RemoveFamilyMemberCommand(tenantId, memberId)).removed());
        assertEquals(1, adapter.getCategoryBudgets(new GetCategoryBudgetsCommand(tenantId)).budgets().size());
        assertEquals("ALIMENTACAO", adapter.setCategoryBudget(new SetCategoryBudgetCommand(tenantId, "ALIMENTACAO", "Alimentação", "1500.0000", 80)).getCategoryId());
        assertTrue(adapter.setCardPdfPassword(new SetCardPdfPasswordCommand(tenantId, cardId, "secret123")).stored());
        assertEquals("exp-1", adapter.exportUserData(new ExportUserDataCommand(tenantId, memberId, "JSON")).exportId());
        assertEquals(tenantId, adapter.requestCryptoShredding(new RequestCryptoShreddingCommand(tenantId, memberId, "EXCLUIR_DEFINITIVAMENTE_MINHA_CONTA", "123456")).getTenantId());
    }
}
