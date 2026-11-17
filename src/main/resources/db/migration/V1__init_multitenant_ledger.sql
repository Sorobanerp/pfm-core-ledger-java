-- ============================================================================
-- Migration: V1__init_multitenant_ledger.sql
-- Target Database: pfm_core_ledger (Port 5432 in pfm-shared-infra)
-- Architecture: Zero-Trust Multi-Tenancy with PostgreSQL 16 Row-Level Security (ADR-006)
-- Monetary Precision: NUMERIC(19,4) for all monetary amounts (RN06)
-- ============================================================================

-- 1. Create restricted application role `pfm_app_user` (NOBYPASSRLS)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pfm_app_user') THEN
        CREATE ROLE pfm_app_user WITH LOGIN PASSWORD 'pfm_app_secret_2026' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END
$$;

-- Helper function that safely resolves `app.current_tenant_id` session variable into a UUID
-- Returns NULL when unset or blank so RLS policies fail-closed (return 0 rows).
CREATE OR REPLACE FUNCTION current_app_tenant_id() RETURNS UUID AS $$
DECLARE
    raw_val TEXT;
BEGIN
    raw_val := NULLIF(BTRIM(current_setting('app.current_tenant_id', true)), '');
    IF raw_val IS NULL THEN
        RETURN NULL;
    END IF;
    RETURN raw_val::UUID;
EXCEPTION
    WHEN OTHERS THEN
        RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER;

-- ============================================================================
-- 2. Reference Table: Official 2-Level Category Taxonomy (Artefato 01 V2)
-- ============================================================================
CREATE TABLE IF NOT EXISTS categories (
    id                  UUID PRIMARY KEY,
    level1_code         VARCHAR(64)  NOT NULL,
    level1_name         VARCHAR(128) NOT NULL,
    nature              VARCHAR(64)  NOT NULL,
    level2_code         VARCHAR(64)  NOT NULL UNIQUE,
    level2_name         VARCHAR(128) NOT NULL,
    is_system_default   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ============================================================================
-- 3. Multitenant Core Tables (100% Enforced with RLS & NUMERIC(19,4))
-- ============================================================================

-- 3.1. tenants
CREATE TABLE IF NOT EXISTS tenants (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL,
    name                VARCHAR(255) NOT NULL,
    tax_id_encrypted    BYTEA,
    dek_key_arn         VARCHAR(255) NOT NULL DEFAULT 'alias/pfm-tenant-master-key',
    base_currency       CHAR(3)      NOT NULL DEFAULT 'BRL',
    monthly_net_income  NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
    status              VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tenants_tenant_id UNIQUE (tenant_id),
    CONSTRAINT chk_tenants_self_id CHECK (id = tenant_id)
);

-- 3.2. tenant_members
CREATE TABLE IF NOT EXISTS tenant_members (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    user_subject        VARCHAR(255) NOT NULL,
    email               VARCHAR(255) NOT NULL,
    role                VARCHAR(32)  NOT NULL DEFAULT 'OWNER',
    spending_allowance  NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tenant_member_sub UNIQUE (tenant_id, user_subject)
);

-- 3.3. categorization_rules
CREATE TABLE IF NOT EXISTS categorization_rules (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    merchant_pattern    VARCHAR(255) NOT NULL,
    level1_code         VARCHAR(64)  NOT NULL,
    level2_code         VARCHAR(64)  NOT NULL REFERENCES categories(level2_code),
    confidence_threshold NUMERIC(19,4) NOT NULL DEFAULT 0.9500,
    priority            INT          NOT NULL DEFAULT 100,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 3.4. financial_accounts
CREATE TABLE IF NOT EXISTS financial_accounts (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID          NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    account_name        VARCHAR(128)  NOT NULL,
    institution_code    VARCHAR(32)   NOT NULL,
    account_type        VARCHAR(32)   NOT NULL,
    currency            CHAR(3)       NOT NULL DEFAULT 'BRL',
    current_balance     NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
    overdraft_limit     NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- 3.5. credit_cards (RN02: Credit Limit != Budget)
CREATE TABLE IF NOT EXISTS credit_cards (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID          NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    financial_account_id UUID         NOT NULL REFERENCES financial_accounts(id) ON DELETE CASCADE,
    card_name           VARCHAR(128)  NOT NULL,
    card_last_four      CHAR(4)       NOT NULL,
    credit_limit        NUMERIC(19,4) NOT NULL,
    available_limit     NUMERIC(19,4) NOT NULL,
    closing_day         INT           NOT NULL CHECK (closing_day BETWEEN 1 AND 31),
    due_day             INT           NOT NULL CHECK (due_day BETWEEN 1 AND 31),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- 3.6. financial_contracts (SAC, PRICE, CONSORCIO)
CREATE TABLE IF NOT EXISTS financial_contracts (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID          NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    contract_name           VARCHAR(128)  NOT NULL,
    modality                VARCHAR(32)   NOT NULL CHECK (modality IN ('SAC', 'PRICE', 'CONSORCIO')),
    principal_balance       NUMERIC(19,4) NOT NULL,
    monthly_interest_rate   NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
    consorcio_admin_fee_rate NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
    monthly_insurance_fees  NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
    total_installments      INT           NOT NULL CHECK (total_installments >= 1),
    remaining_installments  INT           NOT NULL CHECK (remaining_installments >= 0),
    start_date              DATE          NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- 3.7. ledger_entries (RN01: Accrual vs Cash, RN03: Append-Only Refunds, RN06: NUMERIC(19,4))
CREATE TABLE IF NOT EXISTS ledger_entries (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID          NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    account_id              UUID          NOT NULL REFERENCES financial_accounts(id),
    credit_card_id          UUID          REFERENCES credit_cards(id),
    category_code           VARCHAR(64)   NOT NULL,
    subcategory_code        VARCHAR(64)   NOT NULL REFERENCES categories(level2_code),
    direction               VARCHAR(16)   NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    entry_type              VARCHAR(32)   NOT NULL,
    amount                  NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency                CHAR(3)       NOT NULL DEFAULT 'BRL',
    competence_date         DATE          NOT NULL,
    cash_date               DATE,
    description             VARCHAR(512)  NOT NULL,
    idempotency_key         VARCHAR(64)   NOT NULL,
    original_transaction_id UUID          REFERENCES ledger_entries(id),
    reconciliation_status   VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ledger_entries_idempotency UNIQUE (tenant_id, idempotency_key)
);

-- 3.8. installment_schedules (RN01 & RN06: Cent residue on 1st installment)
CREATE TABLE IF NOT EXISTS installment_schedules (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID          NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    credit_card_id          UUID          NOT NULL REFERENCES credit_cards(id) ON DELETE CASCADE,
    parent_ledger_entry_id  UUID          NOT NULL REFERENCES ledger_entries(id) ON DELETE CASCADE,
    installment_number      INT           NOT NULL CHECK (installment_number >= 1),
    total_installments      INT           NOT NULL CHECK (total_installments >= 1),
    competence_month        CHAR(7)       NOT NULL,
    due_date                DATE          NOT NULL,
    amount                  NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    status                  VARCHAR(32)   NOT NULL DEFAULT 'SCHEDULED',
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- 3.9. receipt_items (RN04: NFC-e Reconciliation & EAN >10% Inflation Detection)
CREATE TABLE IF NOT EXISTS receipt_items (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID          NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    ledger_entry_id         UUID          REFERENCES ledger_entries(id) ON DELETE SET NULL,
    access_key              CHAR(44)      NOT NULL,
    item_sequence           INT           NOT NULL,
    ean                     VARCHAR(32)   NOT NULL,
    description             VARCHAR(255)  NOT NULL,
    quantity                NUMERIC(19,4) NOT NULL CHECK (quantity > 0),
    unit_of_measure         VARCHAR(16)   NOT NULL DEFAULT 'UN',
    unit_price              NUMERIC(19,4) NOT NULL CHECK (unit_price >= 0),
    total_price             NUMERIC(19,4) NOT NULL CHECK (total_price >= 0),
    issued_at               TIMESTAMPTZ   NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- 3.10. outbox_events (ADR-005: Transactional Outbox Pattern)
CREATE TABLE IF NOT EXISTS outbox_events (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID          NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    aggregate_type          VARCHAR(64)   NOT NULL,
    aggregate_id            UUID          NOT NULL,
    event_type              VARCHAR(128)  NOT NULL,
    monetary_amount         NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
    idempotency_key         VARCHAR(64)   NOT NULL,
    payload                 JSONB         NOT NULL,
    status                  VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    published_at            TIMESTAMPTZ
);

-- ============================================================================
-- 4. Indexes for Low-Latency RLS Queries (Leading `tenant_id`)
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_tenant_members_tenant ON tenant_members(tenant_id);
CREATE INDEX IF NOT EXISTS idx_categorization_rules_tenant ON categorization_rules(tenant_id, priority);
CREATE INDEX IF NOT EXISTS idx_financial_accounts_tenant ON financial_accounts(tenant_id);
CREATE INDEX IF NOT EXISTS idx_credit_cards_tenant ON credit_cards(tenant_id, financial_account_id);
CREATE INDEX IF NOT EXISTS idx_financial_contracts_tenant ON financial_contracts(tenant_id, modality);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_tenant_competence ON ledger_entries(tenant_id, competence_date DESC);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_reconcile_lookup ON ledger_entries(tenant_id, credit_card_id, amount, competence_date);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_idempotency_lookup ON ledger_entries(tenant_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_installment_schedules_tenant_month ON installment_schedules(tenant_id, competence_month);
CREATE INDEX IF NOT EXISTS idx_receipt_items_tenant_ean ON receipt_items(tenant_id, ean, issued_at DESC);
CREATE INDEX IF NOT EXISTS idx_outbox_events_tenant_status ON outbox_events(tenant_id, status, created_at);

-- ============================================================================
-- 5. Enable & Force Row-Level Security (RLS) Across All Multitenant Tables
-- ============================================================================
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_tenants_isolation ON tenants;
CREATE POLICY rls_tenants_isolation ON tenants
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_app_tenant_id())
    WITH CHECK (tenant_id = current_app_tenant_id());

ALTER TABLE tenant_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_members FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_tenant_members_isolation ON tenant_members;
CREATE POLICY rls_tenant_members_isolation ON tenant_members
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_app_tenant_id())
    WITH CHECK (tenant_id = current_app_tenant_id());

ALTER TABLE categorization_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE categorization_rules FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_categorization_rules_isolation ON categorization_rules;
CREATE POLICY rls_categorization_rules_isolation ON categorization_rules
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_app_tenant_id())
    WITH CHECK (tenant_id = current_app_tenant_id());

ALTER TABLE financial_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE financial_accounts FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_financial_accounts_isolation ON financial_accounts;
CREATE POLICY rls_financial_accounts_isolation ON financial_accounts
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_app_tenant_id())
    WITH CHECK (tenant_id = current_app_tenant_id());

ALTER TABLE credit_cards ENABLE ROW LEVEL SECURITY;
ALTER TABLE credit_cards FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_credit_cards_isolation ON credit_cards;
CREATE POLICY rls_credit_cards_isolation ON credit_cards
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_app_tenant_id())
    WITH CHECK (tenant_id = current_app_tenant_id());

ALTER TABLE financial_contracts ENABLE ROW LEVEL SECURITY;
ALTER TABLE financial_contracts FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_financial_contracts_isolation ON financial_contracts;
CREATE POLICY rls_financial_contracts_isolation ON financial_contracts
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_app_tenant_id())
    WITH CHECK (tenant_id = current_app_tenant_id());

ALTER TABLE ledger_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger_entries FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_ledger_entries_isolation ON ledger_entries;
CREATE POLICY rls_ledger_entries_isolation ON ledger_entries
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_app_tenant_id())
    WITH CHECK (tenant_id = current_app_tenant_id());

ALTER TABLE installment_schedules ENABLE ROW LEVEL SECURITY;
ALTER TABLE installment_schedules FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_installment_schedules_isolation ON installment_schedules;
CREATE POLICY rls_installment_schedules_isolation ON installment_schedules
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_app_tenant_id())
    WITH CHECK (tenant_id = current_app_tenant_id());

ALTER TABLE receipt_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipt_items FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_receipt_items_isolation ON receipt_items;
CREATE POLICY rls_receipt_items_isolation ON receipt_items
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_app_tenant_id())
    WITH CHECK (tenant_id = current_app_tenant_id());

ALTER TABLE outbox_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox_events FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rls_outbox_events_isolation ON outbox_events;
CREATE POLICY rls_outbox_events_isolation ON outbox_events
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_app_tenant_id())
    WITH CHECK (tenant_id = current_app_tenant_id());

-- Grant Least-Privilege DML permissions to `pfm_app_user`
GRANT USAGE ON SCHEMA public TO pfm_app_user;
GRANT SELECT ON categories TO pfm_app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON
    tenants,
    tenant_members,
    categorization_rules,
    financial_accounts,
    credit_cards,
    financial_contracts,
    ledger_entries,
    installment_schedules,
    receipt_items,
    outbox_events
TO pfm_app_user;

-- ============================================================================
-- 6. Official 2-Level Category Taxonomy Seed (Artefato 01 V2 — 10 N1 / 54 N2)
-- ============================================================================
INSERT INTO categories (id, level1_code, level1_name, nature, level2_code, level2_name) VALUES
    -- 1. MORADIA
    ('10000000-0000-0000-0000-000000000001', 'MORADIA', 'Moradia & Utilidades', 'ESSENTIAL_EXPENSE', 'ALUGUEL_CONDOMINIO', 'Aluguel & Condomínio'),
    ('10000000-0000-0000-0000-000000000002', 'MORADIA', 'Moradia & Utilidades', 'ESSENTIAL_EXPENSE', 'FINANCIAMENTO_IMOBILIARIO', 'Financiamento Imobiliário'),
    ('10000000-0000-0000-0000-000000000003', 'MORADIA', 'Moradia & Utilidades', 'ESSENTIAL_EXPENSE', 'ENERGIA_ELETRICA', 'Energia Elétrica'),
    ('10000000-0000-0000-0000-000000000004', 'MORADIA', 'Moradia & Utilidades', 'ESSENTIAL_EXPENSE', 'AGUA_SANEAMENTO', 'Água & Saneamento'),
    ('10000000-0000-0000-0000-000000000005', 'MORADIA', 'Moradia & Utilidades', 'ESSENTIAL_EXPENSE', 'GAS_ENCANADO', 'Gás Encanado'),
    ('10000000-0000-0000-0000-000000000006', 'MORADIA', 'Moradia & Utilidades', 'ESSENTIAL_EXPENSE', 'INTERNET_TV_TELEFONIA', 'Internet, TV & Telefonia'),
    ('10000000-0000-0000-0000-000000000007', 'MORADIA', 'Moradia & Utilidades', 'ESSENTIAL_EXPENSE', 'MANUTENCAO_REPAROS', 'Manutenção & Reparos Residenciais'),
    ('10000000-0000-0000-0000-000000000008', 'MORADIA', 'Moradia & Utilidades', 'ESSENTIAL_EXPENSE', 'IPTU_TAXAS_MUNICIPAIS', 'IPTU & Taxas Municipais'),

    -- 2. ALIMENTACAO
    ('20000000-0000-0000-0000-000000000001', 'ALIMENTACAO', 'Alimentação & Supermercado', 'ESSENTIAL_VARIABLE', 'SUPERMERCADO_MERCEARIA', 'Supermercado & Mercearia'),
    ('20000000-0000-0000-0000-000000000002', 'ALIMENTACAO', 'Alimentação & Supermercado', 'ESSENTIAL_VARIABLE', 'PADARIA_FEIRA_ACOUGUE', 'Padaria, Feira & Açougue'),
    ('20000000-0000-0000-0000-000000000003', 'ALIMENTACAO', 'Alimentação & Supermercado', 'ESSENTIAL_VARIABLE', 'RESTAURANTES_GASTRONOMIA', 'Restaurantes & Gastronomia'),
    ('20000000-0000-0000-0000-000000000004', 'ALIMENTACAO', 'Alimentação & Supermercado', 'ESSENTIAL_VARIABLE', 'DELIVERY_APLICATIVOS', 'Delivery & Aplicativos'),
    ('20000000-0000-0000-0000-000000000005', 'ALIMENTACAO', 'Alimentação & Supermercado', 'ESSENTIAL_VARIABLE', 'CAFE_LANCHES', 'Café & Lanches'),

    -- 3. TRANSPORTE
    ('30000000-0000-0000-0000-000000000001', 'TRANSPORTE', 'Transporte & Mobilidade', 'ESSENTIAL_VARIABLE', 'COMBUSTIVEL_ETANOL_GASOLINA', 'Combustível (Etanol / Gasolina / Diesel)'),
    ('30000000-0000-0000-0000-000000000002', 'TRANSPORTE', 'Transporte & Mobilidade', 'ESSENTIAL_VARIABLE', 'APLICATIVOS_MOBILIDADE', 'Aplicativos de Mobilidade (Uber / 99)'),
    ('30000000-0000-0000-0000-000000000003', 'TRANSPORTE', 'Transporte & Mobilidade', 'ESSENTIAL_VARIABLE', 'TRANSPORTE_PUBLICO', 'Transporte Público'),
    ('30000000-0000-0000-0000-000000000004', 'TRANSPORTE', 'Transporte & Mobilidade', 'ESSENTIAL_VARIABLE', 'ESTACIONAMENTO_PEDAGIO', 'Estacionamento & Pedágio'),
    ('30000000-0000-0000-0000-000000000005', 'TRANSPORTE', 'Transporte & Mobilidade', 'ESSENTIAL_VARIABLE', 'MANUTENCAO_VEICULAR', 'Manutenção Veicular & Peças'),
    ('30000000-0000-0000-0000-000000000006', 'TRANSPORTE', 'Transporte & Mobilidade', 'ESSENTIAL_VARIABLE', 'SEGURO_IPVA_LICENCIAMENTO', 'Seguro Auto, IPVA & Licenciamento'),

    -- 4. SAUDE
    ('40000000-0000-0000-0000-000000000001', 'SAUDE', 'Saúde & Bem-Estar', 'ESSENTIAL_EXPENSE', 'PLANO_SAUDE', 'Plano de Saúde'),
    ('40000000-0000-0000-0000-000000000002', 'SAUDE', 'Saúde & Bem-Estar', 'ESSENTIAL_EXPENSE', 'FARMACIA_MEDICAMENTOS', 'Farmácia & Medicamentos'),
    ('40000000-0000-0000-0000-000000000003', 'SAUDE', 'Saúde & Bem-Estar', 'ESSENTIAL_EXPENSE', 'CONSULTAS_EXAMES', 'Consultas Médicas & Exames'),
    ('40000000-0000-0000-0000-000000000004', 'SAUDE', 'Saúde & Bem-Estar', 'ESSENTIAL_EXPENSE', 'ODONTOLOGIA', 'Odontologia'),
    ('40000000-0000-0000-0000-000000000005', 'SAUDE', 'Saúde & Bem-Estar', 'ESSENTIAL_EXPENSE', 'TERAPIA_PSICOLOGIA', 'Terapia & Psicologia'),
    ('40000000-0000-0000-0000-000000000006', 'SAUDE', 'Saúde & Bem-Estar', 'ESSENTIAL_EXPENSE', 'ACADEMIA_ATIVIDADE_FISICA', 'Academia & Atividade Física'),

    -- 5. EDUCACAO
    ('50000000-0000-0000-0000-000000000001', 'EDUCACAO', 'Educação & Desenvolvimento', 'PERSONAL_INVESTMENT', 'MENSALIDADE_ESCOLAR_FACULDADE', 'Mensalidade Escolar & Faculdade'),
    ('50000000-0000-0000-0000-000000000002', 'EDUCACAO', 'Educação & Desenvolvimento', 'PERSONAL_INVESTMENT', 'CURSOS_CERTIFICACOES', 'Cursos & Certificações'),
    ('50000000-0000-0000-0000-000000000003', 'EDUCACAO', 'Educação & Desenvolvimento', 'PERSONAL_INVESTMENT', 'LIVROS_MATERIAL_DIDATICO', 'Livros & Material Didático'),
    ('50000000-0000-0000-0000-000000000004', 'EDUCACAO', 'Educação & Desenvolvimento', 'PERSONAL_INVESTMENT', 'IDIOMAS', 'Cursos de Idiomas'),

    -- 6. LAZER
    ('60000000-0000-0000-0000-000000000001', 'LAZER', 'Lazer, Cultura & Estilo de Vida', 'LIFESTYLE_VARIABLE', 'STREAMING_ASSINATURAS_DIGITAIS', 'Streaming & Assinaturas Digitais'),
    ('60000000-0000-0000-0000-000000000002', 'LAZER', 'Lazer, Cultura & Estilo de Vida', 'LIFESTYLE_VARIABLE', 'VIAGENS_HOSPEDAGEM', 'Viagens & Hospedagem'),
    ('60000000-0000-0000-0000-000000000003', 'LAZER', 'Lazer, Cultura & Estilo de Vida', 'LIFESTYLE_VARIABLE', 'PASSAGENS_AEREAS', 'Passagens Aéreas'),
    ('60000000-0000-0000-0000-000000000004', 'LAZER', 'Lazer, Cultura & Estilo de Vida', 'LIFESTYLE_VARIABLE', 'CINEMA_SHOWS_EVENTOS', 'Cinema, Shows & Eventos'),
    ('60000000-0000-0000-0000-000000000005', 'LAZER', 'Lazer, Cultura & Estilo de Vida', 'LIFESTYLE_VARIABLE', 'HOBBIES_JOGOS', 'Hobbies & Jogos'),
    ('60000000-0000-0000-0000-000000000006', 'LAZER', 'Lazer, Cultura & Estilo de Vida', 'LIFESTYLE_VARIABLE', 'VESTUARIO_CALCADOS', 'Vestuário & Calçados'),
    ('60000000-0000-0000-0000-000000000007', 'LAZER', 'Lazer, Cultura & Estilo de Vida', 'LIFESTYLE_VARIABLE', 'CUIDADOS_PESSOAIS_BELEZA', 'Cuidados Pessoais & Beleza'),
    ('60000000-0000-0000-0000-000000000008', 'LAZER', 'Lazer, Cultura & Estilo de Vida', 'LIFESTYLE_VARIABLE', 'PETS_VETERINARIO', 'Pets & Veterinário'),

    -- 7. FINANCEIRO
    ('70000000-0000-0000-0000-000000000001', 'FINANCEIRO', 'Serviços Financeiros & Impostos', 'FINANCIAL_EXPENSE', 'IOF_IMPOSTOS_FINANCEIROS', 'IOF & Impostos Financeiros'),
    ('70000000-0000-0000-0000-000000000002', 'FINANCEIRO', 'Serviços Financeiros & Impostos', 'FINANCIAL_EXPENSE', 'ANUIDADE_TARIFAS_BANCARIAS', 'Anuidade & Tarifas Bancárias'),
    ('70000000-0000-0000-0000-000000000003', 'FINANCEIRO', 'Serviços Financeiros & Impostos', 'FINANCIAL_EXPENSE', 'JUROS_ENCARGOS_ROTATIVO', 'Juros & Encargos de Rotativo'),
    ('70000000-0000-0000-0000-000000000004', 'FINANCEIRO', 'Serviços Financeiros & Impostos', 'FINANCIAL_EXPENSE', 'SEGURO_VIDA_PATRIMONIAL', 'Seguro de Vida & Patrimonial'),
    ('70000000-0000-0000-0000-000000000005', 'FINANCEIRO', 'Serviços Financeiros & Impostos', 'FINANCIAL_EXPENSE', 'IRPF_TRIBUTOS_FEDERAIS', 'IRPF & Tributos Federais'),

    -- 8. RECEITAS
    ('80000000-0000-0000-0000-000000000001', 'RECEITAS', 'Receitas & Entradas', 'INCOME', 'SALARIO_PRO_LABORE', 'Salário & Pró-Labore'),
    ('80000000-0000-0000-0000-000000000002', 'RECEITAS', 'Receitas & Entradas', 'INCOME', 'BONUS_PLR_13_SALARIO', 'Bônus, PLR & 13º Salário'),
    ('80000000-0000-0000-0000-000000000003', 'RECEITAS', 'Receitas & Entradas', 'INCOME', 'RENDIMENTOS_DIVIDENDOS', 'Rendimentos & Dividendos'),
    ('80000000-0000-0000-0000-000000000004', 'RECEITAS', 'Receitas & Entradas', 'INCOME', 'FREELANCE_PRESTACAO_SERVICOS', 'Freelance & Prestação de Serviços'),
    ('80000000-0000-0000-0000-000000000005', 'RECEITAS', 'Receitas & Entradas', 'INCOME', 'REEMBOLSO_CORPORATIVO', 'Reembolso Corporativo'),
    ('80000000-0000-0000-0000-000000000006', 'RECEITAS', 'Receitas & Entradas', 'INCOME', 'ESTORNO_CREDITO', 'Estorno & Crédito em Fatura'),

    -- 9. PATRIMONIO
    ('90000000-0000-0000-0000-000000000001', 'PATRIMONIO', 'Investimentos & Movimentações Neutras', 'NEUTRAL_PATRIMONIAL', 'APORTE_RENDA_FIXA', 'Aporte em Renda Fixa'),
    ('90000000-0000-0000-0000-000000000002', 'PATRIMONIO', 'Investimentos & Movimentações Neutras', 'NEUTRAL_PATRIMONIAL', 'APORTE_RENDA_VARIAVEL', 'Aporte em Renda Variável'),
    ('90000000-0000-0000-0000-000000000003', 'PATRIMONIO', 'Investimentos & Movimentações Neutras', 'NEUTRAL_PATRIMONIAL', 'RESERVA_EMERGENCIA', 'Reserva de Emergência'),
    ('90000000-0000-0000-0000-000000000004', 'PATRIMONIO', 'Investimentos & Movimentações Neutras', 'NEUTRAL_PATRIMONIAL', 'PAGAMENTO_FATURA_CARTAO', 'Pagamento de Fatura de Cartão'),
    ('90000000-0000-0000-0000-000000000005', 'PATRIMONIO', 'Investimentos & Movimentações Neutras', 'NEUTRAL_PATRIMONIAL', 'TRANSFERENCIA_MESMA_TITULARIDADE', 'Transferência de Mesma Titularidade'),

    -- 10. OUTROS
    ('a0000000-0000-0000-0000-000000000001', 'OUTROS', 'Outros & Pendências de Classificação', 'TRANSITORY', 'DOACOES_PRESENTES', 'Doações & Presentes'),
    ('a0000000-0000-0000-0000-000000000002', 'OUTROS', 'Outros & Pendências de Classificação', 'TRANSITORY', 'DESPESAS_EVENTUAIS', 'Despesas Eventuais'),
    ('a0000000-0000-0000-0000-000000000003', 'OUTROS', 'Outros & Pendências de Classificação', 'TRANSITORY', 'NAO_CATEGORIZADO', 'Não Categorizado')
ON CONFLICT (level2_code) DO NOTHING;
