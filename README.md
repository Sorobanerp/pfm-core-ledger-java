# `pfm-core-ledger-java`

> **Core Banking Ledger Service — Java 25 LTS, Spring Boot (Virtual Threads), Hexagonal Architecture & PostgreSQL 16 RLS**

- **Stack Homologada (ADR-001 / ADR-006)**: Java 25 LTS (`ScopedValue`, Virtual Threads), Spring Boot 3.5, Spring Data JDBC/JPA, Flyway, Resilience4j, OpenTelemetry e Testcontainers (PostgreSQL 16).
- **Banco de Dados (`pfm-shared-infra`)**: `pfm_core_ledger` (porta `5432`) com **Row-Level Security (RLS)** forçado em 100% das tabelas multitenant sob a role restrita `pfm_app_user` (`NOBYPASSRLS`) e precisão monetária `NUMERIC(19,4)` (RN06).

---

## 1. Estrutura Hexagonal (Ports & Adapters)

```text
src/main/java/br/com/pfm/ledger/
├── domain/                         # Domínio puro (Zero dependências de Spring/JPA/Frameworks)
│   ├── Money.java                  # BigDecimal escala 4 + RoundingMode.HALF_EVEN + split de parcelas (RN06)
│   ├── LedgerEntry.java            # Segregação Competência vs Caixa (RN01) & Estorno Append-Only (RN03)
│   ├── CreditCard.java             # Limite Total/Disponível segregado do Orçamento (RN02)
│   ├── InstallmentSchedule.java    # Cronograma de parcelas por competência (RN01/RN06)
│   └── FinancialContract.java      # Contratos SAC, Price e Consórcio
├── application/
│   ├── port/in/                    # Use Cases de entrada (RecordLedgerEntryUseCase)
│   ├── port/out/                   # Portas de saída (Repositório RLS, Transactional Outbox, KMS)
│   └── service/                    # Orquestração transacional ACID + Outbox
├── adapter/
│   ├── in/                         # Adaptadores Inbound (gRPC/Kafka com Resilience4j)
│   └── out/                        # Adaptadores Outbound (PostgreSQL RLS + Outbox + AES-256-GCM KMS)
└── infrastructure/
    ├── TenantContextHolder.java    # ScopedValue nativo do Java 25 para isolamento em Virtual Threads
    ├── RlsTenantJdbcInterceptor.java # Interceptor JDBC que executa SET LOCAL app.current_tenant_id = '<uuid>'
    └── RlsDataSourceConfig.java    # Configuração do DataSource envelopado com RLS
```

---

## 2. Execução de Testes & Build

```bash
# Executar a suíte de testes de integração com Testcontainers (PostgreSQL 16 RLS)
./mvnw test

# Construir imagem multi-stage Distroless
docker build -t sorobanerp/pfm-core-ledger-java:latest .
```
