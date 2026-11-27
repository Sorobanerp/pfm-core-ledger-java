package br.com.pfm.ledger.adapter.out.postgres;

import br.com.pfm.ledger.application.port.out.UserProfileRepositoryPort;
import br.com.pfm.ledger.domain.CategoryBudgetRule;
import br.com.pfm.ledger.domain.FamilyMemberProfile;
import br.com.pfm.ledger.domain.FamilyRole;
import br.com.pfm.ledger.domain.LgpdErasureRecord;
import br.com.pfm.ledger.domain.Money;
import br.com.pfm.ledger.infrastructure.TenantContextHolder;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Outbound Adapter implementing PostgreSQL RLS-aware persistence for Profiles, Budgets & LGPD (US-13).
 */
@Repository
public class PostgresRlsUserProfileAdapter implements UserProfileRepositoryPort {

    private final JdbcTemplate jdbcTemplate;

    public PostgresRlsUserProfileAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private void enforceSessionContext() {
        Optional<UUID> tenantIdOpt = TenantContextHolder.getTenantId();
        String safeUuid = tenantIdOpt.map(id -> UUID.fromString(id.toString()).toString()).orElse("");
        String isAdmin = TenantContextHolder.isPlatformAdmin() ? "true" : "false";
        jdbcTemplate.queryForObject("SELECT set_config('app.current_tenant_id', ?, true)", String.class, safeUuid);
        jdbcTemplate.queryForObject("SELECT set_config('app.is_platform_admin', ?, true)", String.class, isAdmin);
    }

    @Override
    public Optional<FamilyMemberProfile> findMemberBySubject(UUID tenantId, String userSubject) {
        enforceSessionContext();
        final String sql = """
                SELECT id, tenant_id, user_subject, email, full_name, role,
                       spending_allowance, status, invitation_token, invitation_expires_at,
                       created_at, updated_at
                FROM tenant_members
                WHERE tenant_id = ? AND (user_subject = ? OR email = ?)
                """;
        List<FamilyMemberProfile> results = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> mapMember(rs),
                tenantId, userSubject, userSubject
        );
        return results.stream().findFirst();
    }

    @Override
    public Optional<FamilyMemberProfile> findMemberById(UUID tenantId, UUID memberId) {
        enforceSessionContext();
        final String sql = """
                SELECT id, tenant_id, user_subject, email, full_name, role,
                       spending_allowance, status, invitation_token, invitation_expires_at,
                       created_at, updated_at
                FROM tenant_members
                WHERE tenant_id = ? AND id = ?
                """;
        List<FamilyMemberProfile> results = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> mapMember(rs),
                tenantId, memberId
        );
        return results.stream().findFirst();
    }

    @Override
    public FamilyMemberProfile saveMember(FamilyMemberProfile member) {
        enforceSessionContext();
        final String sql = """
                INSERT INTO tenant_members (
                    id, tenant_id, user_subject, email, full_name, role,
                    spending_allowance, status, invitation_token, invitation_expires_at,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_subject) DO UPDATE SET
                    email = EXCLUDED.email,
                    full_name = EXCLUDED.full_name,
                    role = EXCLUDED.role,
                    spending_allowance = EXCLUDED.spending_allowance,
                    status = EXCLUDED.status,
                    invitation_token = EXCLUDED.invitation_token,
                    invitation_expires_at = EXCLUDED.invitation_expires_at,
                    updated_at = EXCLUDED.updated_at
                """;
        jdbcTemplate.update(
                sql,
                member.getId(),
                member.getTenantId(),
                member.getUserSubject(),
                member.getEmail(),
                member.getFullName(),
                member.getRole().name(),
                member.getSpendingAllowance().amount(),
                member.getStatus(),
                member.getInvitationToken(),
                member.getInvitationExpiresAt() != null ? Timestamp.from(member.getInvitationExpiresAt()) : null,
                Timestamp.from(member.getCreatedAt()),
                Timestamp.from(member.getUpdatedAt())
        );
        return member;
    }

    @Override
    public List<FamilyMemberProfile> listMembersByTenantId(UUID tenantId) {
        enforceSessionContext();
        final String sql = """
                SELECT id, tenant_id, user_subject, email, full_name, role,
                       spending_allowance, status, invitation_token, invitation_expires_at,
                       created_at, updated_at
                FROM tenant_members
                WHERE tenant_id = ?
                ORDER BY created_at ASC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapMember(rs), tenantId);
    }

    @Override
    public boolean removeMember(UUID tenantId, UUID memberId) {
        enforceSessionContext();
        final String sql = "DELETE FROM tenant_members WHERE tenant_id = ? AND id = ?";
        int rows = jdbcTemplate.update(sql, tenantId, memberId);
        return rows > 0;
    }

    @Override
    public List<CategoryBudgetRule> listCategoryBudgetsByTenantId(UUID tenantId) {
        enforceSessionContext();
        final String sql = """
                SELECT id, tenant_id, category_id, category_name, monthly_cap,
                       alert_threshold_percentage, created_at, updated_at
                FROM category_budgets
                WHERE tenant_id = ?
                ORDER BY category_name ASC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapBudget(rs), tenantId);
    }

    @Override
    public CategoryBudgetRule saveCategoryBudget(CategoryBudgetRule budget) {
        enforceSessionContext();
        final String sql = """
                INSERT INTO category_budgets (
                    id, tenant_id, category_id, category_name, monthly_cap,
                    alert_threshold_percentage, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, category_id) DO UPDATE SET
                    category_name = EXCLUDED.category_name,
                    monthly_cap = EXCLUDED.monthly_cap,
                    alert_threshold_percentage = EXCLUDED.alert_threshold_percentage,
                    updated_at = EXCLUDED.updated_at
                """;
        jdbcTemplate.update(
                sql,
                budget.getId(),
                budget.getTenantId(),
                budget.getCategoryId(),
                budget.getCategoryName(),
                budget.getMonthlyCap().amount(),
                budget.getAlertThresholdPercentage(),
                Timestamp.from(budget.getCreatedAt()),
                Timestamp.from(budget.getUpdatedAt())
        );
        return budget;
    }

    @Override
    public void saveCardPdfPassword(UUID tenantId, UUID cardId, byte[] encryptedPassword, String keyId) {
        enforceSessionContext();
        final String sql = """
                INSERT INTO card_pdf_passwords (
                    id, tenant_id, card_id, encrypted_password, key_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                ON CONFLICT (tenant_id, card_id) DO UPDATE SET
                    encrypted_password = EXCLUDED.encrypted_password,
                    key_id = EXCLUDED.key_id,
                    updated_at = NOW()
                """;
        jdbcTemplate.update(sql, UUID.randomUUID(), tenantId, cardId, encryptedPassword, keyId);
    }

    @Override
    public LgpdErasureRecord saveLgpdErasureRecord(LgpdErasureRecord record) {
        enforceSessionContext();
        final String sql = """
                INSERT INTO lgpd_erasure_requests (
                    id, tenant_id, user_id, confirmation_phrase, status,
                    destroyed_key_id, shredded_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(
                sql,
                record.getId(),
                record.getTenantId(),
                record.getUserId(),
                record.getConfirmationPhrase(),
                record.getStatus(),
                record.getDestroyedKeyId(),
                record.getShreddedAt() != null ? Timestamp.from(record.getShreddedAt()) : null,
                Timestamp.from(record.getCreatedAt()),
                Timestamp.from(record.getUpdatedAt())
        );
        return record;
    }

    @Override
    public void purgeTenantDataForCryptoShredding(UUID tenantId) {
        enforceSessionContext();
        jdbcTemplate.update("DELETE FROM card_pdf_passwords WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("UPDATE tenants SET status = 'CRYPTO_SHREDDED', tax_id_encrypted = NULL, name = 'ANONYMIZED_TENANT' WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("UPDATE tenant_members SET email = 'shredded@pfm.local', full_name = 'ANONYMIZED' WHERE tenant_id = ?", tenantId);
    }

    private FamilyMemberProfile mapMember(ResultSet rs) throws SQLException {
        Timestamp invitedTs = rs.getTimestamp("invitation_expires_at");
        Timestamp createdAtTs = rs.getTimestamp("created_at");
        Timestamp updatedAtTs = rs.getTimestamp("updated_at");
        BigDecimal allowance = rs.getBigDecimal("spending_allowance");

        return new FamilyMemberProfile(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("user_subject"),
                rs.getString("email"),
                rs.getString("full_name"),
                FamilyRole.valueOf(rs.getString("role")),
                Money.of(allowance != null ? allowance : BigDecimal.ZERO, "BRL"),
                rs.getString("status"),
                rs.getString("invitation_token"),
                invitedTs != null ? invitedTs.toInstant() : null,
                createdAtTs != null ? createdAtTs.toInstant() : Instant.now(),
                updatedAtTs != null ? updatedAtTs.toInstant() : Instant.now()
        );
    }

    private CategoryBudgetRule mapBudget(ResultSet rs) throws SQLException {
        Timestamp createdAtTs = rs.getTimestamp("created_at");
        Timestamp updatedAtTs = rs.getTimestamp("updated_at");
        BigDecimal cap = rs.getBigDecimal("monthly_cap");

        return new CategoryBudgetRule(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("category_id"),
                rs.getString("category_name"),
                Money.of(cap != null ? cap : BigDecimal.ZERO, "BRL"),
                rs.getInt("alert_threshold_percentage"),
                createdAtTs != null ? createdAtTs.toInstant() : Instant.now(),
                updatedAtTs != null ? updatedAtTs.toInstant() : Instant.now()
        );
    }
}
