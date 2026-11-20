-- ============================================================================
-- Migration: V2__tenant_subscriptions_and_profiles.sql
-- Module: US-12 (SaaS Admin & Subscriptions) & US-13 (Profile, Family & LGPD)
-- Architecture: Zero-Trust Multi-Tenancy with PostgreSQL 16 RLS (ADR-006)
-- Precision: NUMERIC(19,4) for monetary caps and spending allowances (RN06)
-- ============================================================================

-- 1. Helper function for platform admin session check
CREATE OR REPLACE FUNCTION is_platform_admin() RETURNS BOOLEAN AS $$
BEGIN
    RETURN COALESCE(current_setting('app.is_platform_admin', true), 'false') = 'true';
EXCEPTION
    WHEN OTHERS THEN
        RETURN FALSE;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER;

-- 2. Alter tenants table for plan & metadata
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS plan VARCHAR(32) NOT NULL DEFAULT 'FREE';
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Update tenants RLS policy to allow platform admin global listing
DROP POLICY IF EXISTS rls_tenants_isolation ON tenants;
CREATE POLICY rls_tenants_isolation ON tenants
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_app_tenant_id() OR is_platform_admin())
    WITH CHECK (tenant_id = current_app_tenant_id() OR is_platform_admin());

-- 3. Subscriptions table (US-12)
CREATE TABLE IF NOT EXISTS subscriptions (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    plan                VARCHAR(32)  NOT NULL DEFAULT 'FREE' CHECK (plan IN ('FREE', 'INDIVIDUAL_PRO', 'FAMILY_PLUS')),
    status              VARCHAR(32)  NOT NULL DEFAULT 'TRIAL' CHECK (status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'SUSPENDED')),
    max_members         INT          NOT NULL DEFAULT 1,
    max_cards           INT          NOT NULL DEFAULT 1,
    monthly_pdf_quota   INT          NOT NULL DEFAULT 3,
    used_pdf_quota      INT          NOT NULL DEFAULT 0,
    trial_ends_at       TIMESTAMPTZ,
    current_period_end  TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_subscriptions_tenant UNIQUE (tenant_id)
);

CREATE INDEX IF NOT EXISTS idx_subscriptions_tenant ON subscriptions(tenant_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_status ON subscriptions(status);

ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscriptions FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS rls_subscriptions_isolation ON subscriptions;
CREATE POLICY rls_subscriptions_isolation ON subscriptions
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_app_tenant_id() OR is_platform_admin())
    WITH CHECK (tenant_id = current_app_tenant_id() OR is_platform_admin());

-- 4. Alter tenant_members for full name, status and invitations (US-13)
ALTER TABLE tenant_members ADD COLUMN IF NOT EXISTS full_name VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE tenant_members ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE tenant_members ADD COLUMN IF NOT EXISTS invitation_token VARCHAR(255);
ALTER TABLE tenant_members ADD COLUMN IF NOT EXISTS invitation_expires_at TIMESTAMPTZ;
ALTER TABLE tenant_members ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- 5. Category Budgets table (US-13)
CREATE TABLE IF NOT EXISTS category_budgets (
    id                          UUID PRIMARY KEY,
    tenant_id                   UUID          NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    category_id                 VARCHAR(64)   NOT NULL,
    category_name               VARCHAR(128)  NOT NULL,
    monthly_cap                 NUMERIC(19,4) NOT NULL DEFAULT 0.0000 CHECK (monthly_cap >= 0),
    alert_threshold_percentage  INT           NOT NULL DEFAULT 80 CHECK (alert_threshold_percentage BETWEEN 1 AND 100),
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_category_budgets_tenant_cat UNIQUE (tenant_id, category_id)
);

CREATE INDEX IF NOT EXISTS idx_category_budgets_tenant ON category_budgets(tenant_id, category_id);

ALTER TABLE category_budgets ENABLE ROW LEVEL SECURITY;
ALTER TABLE category_budgets FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS rls_category_budgets_isolation ON category_budgets;
CREATE POLICY rls_category_budgets_isolation ON category_budgets
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_app_tenant_id())
    WITH CHECK (tenant_id = current_app_tenant_id());

-- 6. Encrypted Card PDF Passwords Vault (US-13)
CREATE TABLE IF NOT EXISTS card_pdf_passwords (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    card_id             UUID         NOT NULL REFERENCES credit_cards(id) ON DELETE CASCADE,
    encrypted_password  BYTEA        NOT NULL,
    key_id              VARCHAR(255) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_card_pdf_passwords_tenant_card UNIQUE (tenant_id, card_id)
);

CREATE INDEX IF NOT EXISTS idx_card_pdf_passwords_tenant ON card_pdf_passwords(tenant_id, card_id);

ALTER TABLE card_pdf_passwords ENABLE ROW LEVEL SECURITY;
ALTER TABLE card_pdf_passwords FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS rls_card_pdf_passwords_isolation ON card_pdf_passwords;
CREATE POLICY rls_card_pdf_passwords_isolation ON card_pdf_passwords
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_app_tenant_id())
    WITH CHECK (tenant_id = current_app_tenant_id());

-- 7. LGPD Erasure Requests & Crypto-Shredding Audit (US-13)
CREATE TABLE IF NOT EXISTS lgpd_erasure_requests (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    user_id             UUID         NOT NULL,
    confirmation_phrase VARCHAR(255) NOT NULL,
    status              VARCHAR(32)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CRYPTO_SHREDDED', 'FAILED')),
    destroyed_key_id    VARCHAR(255),
    shredded_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_lgpd_erasure_tenant ON lgpd_erasure_requests(tenant_id, status);

ALTER TABLE lgpd_erasure_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE lgpd_erasure_requests FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS rls_lgpd_erasure_isolation ON lgpd_erasure_requests;
CREATE POLICY rls_lgpd_erasure_isolation ON lgpd_erasure_requests
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_app_tenant_id())
    WITH CHECK (tenant_id = current_app_tenant_id());

-- Grant DML to pfm_app_user
GRANT SELECT, INSERT, UPDATE, DELETE ON subscriptions, category_budgets, card_pdf_passwords, lgpd_erasure_requests TO pfm_app_user;
