-- ============================================================================
-- Migration: V3__immutable_audit_log.sql
-- Module: ADR-010 (Observabilidade Multi-Cloud Unificada & Auditoria Imutável)
-- Architecture: Append-Only Audit Trail protected by PostgreSQL 16 FORCE RLS
--               and DO INSTEAD NOTHING rules for UPDATE / DELETE.
-- ============================================================================

CREATE TABLE IF NOT EXISTS audit_log (
    id              UUID          PRIMARY KEY,
    tenant_id       UUID          NOT NULL,
    actor_id        VARCHAR(255)  NOT NULL,
    actor_role      VARCHAR(64)   NOT NULL,
    action          VARCHAR(128)  NOT NULL,
    resource_type   VARCHAR(64)   NOT NULL,
    resource_id     VARCHAR(255),
    trace_id        VARCHAR(64)   NOT NULL,
    metadata        JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_tenant_created ON audit_log(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_trace_id ON audit_log(trace_id);

-- Strict Row-Level Security (ADR-006 & ADR-010)
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS rls_audit_log_isolation ON audit_log;
CREATE POLICY rls_audit_log_isolation ON audit_log
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_app_tenant_id() OR is_platform_admin())
    WITH CHECK (tenant_id = current_app_tenant_id() OR is_platform_admin());

-- Immutability rules: append-only (UPDATE and DELETE silently do nothing)
CREATE OR REPLACE RULE audit_log_no_update AS
    ON UPDATE TO audit_log DO INSTEAD NOTHING;

CREATE OR REPLACE RULE audit_log_no_delete AS
    ON DELETE TO audit_log DO INSTEAD NOTHING;

-- Least-privilege grants: only SELECT and INSERT for application user
REVOKE ALL ON audit_log FROM pfm_app_user;
GRANT SELECT, INSERT ON audit_log TO pfm_app_user;
