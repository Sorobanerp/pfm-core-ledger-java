package br.com.pfm.ledger.adapter.in.grpc;

import br.com.pfm.ledger.application.port.in.UserProfileUseCase;
import br.com.pfm.ledger.application.port.in.UserProfileUseCase.ExportDataResult;
import br.com.pfm.ledger.domain.CategoryBudgetRule;
import br.com.pfm.ledger.domain.FamilyMemberProfile;
import br.com.pfm.ledger.domain.FamilyRole;
import br.com.pfm.ledger.domain.LgpdErasureRecord;
import br.com.pfm.ledger.domain.Money;
import br.com.pfm.ledger.infrastructure.TenantContextHolder;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound gRPC / HTTP Server Adapter for User Profile, Family RBAC & LGPD (US-13).
 */
@RestController
@RequestMapping("/pfm.v1.UserProfileService")
public class UserProfileGrpcServerAdapter {

    private final UserProfileUseCase profileUseCase;
    private final ExecutorService virtualExecutor;

    public UserProfileGrpcServerAdapter(UserProfileUseCase profileUseCase) {
        this.profileUseCase = Objects.requireNonNull(profileUseCase, "profileUseCase must not be null");
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public record GetUserProfileCommand(UUID tenantId, String userSubject) {}

    @PostMapping("/GetUserProfile")
    public FamilyMemberProfile getUserProfile(@RequestBody GetUserProfileCommand command) throws Exception {
        Objects.requireNonNull(command, "command must not be null");
        Future<FamilyMemberProfile> future = virtualExecutor.submit(() ->
                TenantContextHolder.callWithTenant(command.tenantId(), () ->
                        profileUseCase.getUserProfile(command.tenantId(), command.userSubject())
                )
        );
        return future.get();
    }

    public record UpdateUserProfileCommand(UUID tenantId, String userSubject, String fullName) {}

    @PostMapping("/UpdateUserProfile")
    public FamilyMemberProfile updateUserProfile(@RequestBody UpdateUserProfileCommand command) throws Exception {
        Objects.requireNonNull(command, "command must not be null");
        Future<FamilyMemberProfile> future = virtualExecutor.submit(() ->
                TenantContextHolder.callWithTenant(command.tenantId(), () ->
                        profileUseCase.updateUserProfile(command.tenantId(), command.userSubject(), command.fullName())
                )
        );
        return future.get();
    }

    public record ListFamilyMembersCommand(UUID tenantId) {}
    public record ListFamilyMembersResponseDTO(List<FamilyMemberProfile> members) {}

    @PostMapping("/ListFamilyMembers")
    public ListFamilyMembersResponseDTO listFamilyMembers(@RequestBody ListFamilyMembersCommand command) throws Exception {
        Objects.requireNonNull(command, "command must not be null");
        Future<List<FamilyMemberProfile>> future = virtualExecutor.submit(() ->
                TenantContextHolder.callWithTenant(command.tenantId(), () ->
                        profileUseCase.listFamilyMembers(command.tenantId())
                )
        );
        return new ListFamilyMembersResponseDTO(future.get());
    }

    public record InviteFamilyMemberCommand(UUID tenantId, String email, String fullName, String role, String spendingAllowance) {}

    @PostMapping("/InviteFamilyMember")
    public FamilyMemberProfile inviteFamilyMember(@RequestBody InviteFamilyMemberCommand command) throws Exception {
        Objects.requireNonNull(command, "command must not be null");
        FamilyRole role = FamilyRole.valueOf(command.role());
        Money allowance = Money.of(command.spendingAllowance() != null ? command.spendingAllowance() : "0.0000", "BRL");

        Future<FamilyMemberProfile> future = virtualExecutor.submit(() ->
                TenantContextHolder.callWithTenant(command.tenantId(), () ->
                        profileUseCase.inviteFamilyMember(command.tenantId(), command.email(), command.fullName(), role, allowance)
                )
        );
        return future.get();
    }

    public record RemoveFamilyMemberCommand(UUID tenantId, UUID memberId) {}
    public record RemoveFamilyMemberResponseDTO(UUID tenantId, UUID memberId, boolean removed) {}

    @PostMapping("/RemoveFamilyMember")
    public RemoveFamilyMemberResponseDTO removeFamilyMember(@RequestBody RemoveFamilyMemberCommand command) throws Exception {
        Objects.requireNonNull(command, "command must not be null");
        Future<Boolean> future = virtualExecutor.submit(() ->
                TenantContextHolder.callWithTenant(command.tenantId(), () ->
                        profileUseCase.removeFamilyMember(command.tenantId(), command.memberId())
                )
        );
        return new RemoveFamilyMemberResponseDTO(command.tenantId(), command.memberId(), future.get());
    }

    public record GetCategoryBudgetsCommand(UUID tenantId) {}
    public record GetCategoryBudgetsResponseDTO(List<CategoryBudgetRule> budgets) {}

    @PostMapping("/GetCategoryBudgets")
    public GetCategoryBudgetsResponseDTO getCategoryBudgets(@RequestBody GetCategoryBudgetsCommand command) throws Exception {
        Objects.requireNonNull(command, "command must not be null");
        Future<List<CategoryBudgetRule>> future = virtualExecutor.submit(() ->
                TenantContextHolder.callWithTenant(command.tenantId(), () ->
                        profileUseCase.getCategoryBudgets(command.tenantId())
                )
        );
        return new GetCategoryBudgetsResponseDTO(future.get());
    }

    public record SetCategoryBudgetCommand(UUID tenantId, String categoryId, String categoryName, String monthlyCap, int thresholdPercentage) {}

    @PostMapping("/SetCategoryBudget")
    public CategoryBudgetRule setCategoryBudget(@RequestBody SetCategoryBudgetCommand command) throws Exception {
        Objects.requireNonNull(command, "command must not be null");
        Money cap = Money.of(command.monthlyCap(), "BRL");

        Future<CategoryBudgetRule> future = virtualExecutor.submit(() ->
                TenantContextHolder.callWithTenant(command.tenantId(), () ->
                        profileUseCase.setCategoryBudget(command.tenantId(), command.categoryId(), command.categoryName(), cap, command.thresholdPercentage())
                )
        );
        return future.get();
    }

    public record SetCardPdfPasswordCommand(UUID tenantId, UUID cardId, String password) {}
    public record SetCardPdfPasswordResponseDTO(UUID tenantId, UUID cardId, boolean stored) {}

    @PostMapping("/SetCardPdfPassword")
    public SetCardPdfPasswordResponseDTO setCardPdfPassword(@RequestBody SetCardPdfPasswordCommand command) throws Exception {
        Objects.requireNonNull(command, "command must not be null");
        Future<Boolean> future = virtualExecutor.submit(() ->
                TenantContextHolder.callWithTenant(command.tenantId(), () ->
                        profileUseCase.setCardPdfPassword(command.tenantId(), command.cardId(), command.password())
                )
        );
        return new SetCardPdfPasswordResponseDTO(command.tenantId(), command.cardId(), future.get());
    }

    public record ExportUserDataCommand(UUID tenantId, UUID userId, String format) {}

    @PostMapping("/ExportUserData")
    public ExportDataResult exportUserData(@RequestBody ExportUserDataCommand command) throws Exception {
        Objects.requireNonNull(command, "command must not be null");
        Future<ExportDataResult> future = virtualExecutor.submit(() ->
                TenantContextHolder.callWithTenant(command.tenantId(), () ->
                        profileUseCase.exportUserData(command.tenantId(), command.userId(), command.format() != null ? command.format() : "JSON")
                )
        );
        return future.get();
    }

    public record RequestCryptoShreddingCommand(UUID tenantId, UUID userId, String confirmationPhrase, String mfaToken) {}

    @PostMapping("/RequestCryptoShredding")
    public LgpdErasureRecord requestCryptoShredding(@RequestBody RequestCryptoShreddingCommand command) throws Exception {
        Objects.requireNonNull(command, "command must not be null");
        Future<LgpdErasureRecord> future = virtualExecutor.submit(() ->
                TenantContextHolder.callWithTenant(command.tenantId(), () ->
                        profileUseCase.requestCryptoShredding(command.tenantId(), command.userId(), command.confirmationPhrase(), command.mfaToken())
                )
        );
        return future.get();
    }
}
