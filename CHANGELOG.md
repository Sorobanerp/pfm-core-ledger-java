# Changelog — `pfm-core-ledger-java`

Todas as alterações notáveis neste repositório são documentadas neste arquivo seguindo o padrão [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e versionamento semântico [SemVer 2.0.0](https://semver.org/lang/pt-BR/).

---

## [Unreleased]

### Added
- Migration Flyway `V3__immutable_audit_log.sql`: tabela `audit_log` append-only protegida por PostgreSQL **FORCE RLS** (`app.current_tenant_id`) e regras `DO INSTEAD NOTHING` para `UPDATE` e `DELETE` (**ADR-010**).
- Camada hexagonal de auditoria imutável: `AuditLogEntry` (domínio puro), `AuditLogRepositoryPort` (`port/out`) e `PostgresRlsAuditLogAdapter` (`adapter/out/postgres` com Resilience4j `CircuitBreaker` + `Bulkhead`).
- Gravação automática de trilha de auditoria (`audit_log`) com `trace_id` OpenTelemetry nos casos de uso `US-12` (`TenantAdminApplicationService`) e `US-13` (`UserProfileApplicationService`), validada via Testcontainers (`ImmutableAuditLogIT`).
- Padronização de governança DevEx (`README.md`, `CHANGELOG.md`, `CONTRIBUTING.md`) e workflow de validação de **Conventional Commits** em Pull Requests para `develop` e `main`.

---

## [0.2.0] - 2026-11-20

### Added
- Regras de negócio `US-12` (SaaS Admin sem PII) e `US-13` (Gestão Familiar RBAC, Exportação LGPD e Crypto-Shredding) implementadas no núcleo hexagonal.

### Changed
- Alinhamento com a arquitetura **PFM Core Banking V3.0 (Production-Ready FinOps & Multi-Cloud)**.

### Security
- Aplicação estrita de políticas Zero-Trust (OIDC sem chaves estáticas, Trivy/Semgrep/Gitleaks gates e isolamento por tenant).

### Infrastructure
- Instrumentação OpenTelemetry OTLP e cobertura de testes elevada para Quality Gate $\ge 95\%$.

---

## [0.1.0] - 2026-11-15

### Added
- Estrutura inicial do repositório `pfm-core-ledger-java`, pipelines GitHub Actions e integração ao ecossistema Gitflow `Sorobanerp`.
