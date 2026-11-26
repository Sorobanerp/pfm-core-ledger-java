#!/usr/bin/env bash
# ==============================================================================
# PFM PULSE — LOCAL FOREGROUND RUNNER & LIVE DATADOG JSON LOGGER
# Service: pfm-core-ledger-java (Java 25 LTS | gRPC :9090 | HTTP :8082)
# CloudWatch: 100% DISABLED (AWS_CLOUDWATCH_ENABLED=false)
# ==============================================================================
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p logs

if [[ -f ".env.local" ]]; then
  set -a
  # shellcheck disable=SC1091
  source ".env.local"
  set +a
fi

export SPRING_PROFILES_ACTIVE="local"
export APP_ENV="local"
export LOG_LEVEL="DEBUG"
export AWS_CLOUDWATCH_ENABLED="false"
export CLOUDWATCH_LOGS_ENABLED="false"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:/opt/maven/bin:/opt/apache-maven-3.9.12/bin:$HOME/.sdkman/candidates/maven/current/bin:$HOME/.sdkman/candidates/java/current/bin:$PATH"

MVN_CMD="mvn"
if [[ -x "./mvnw" ]]; then
  MVN_CMD="./mvnw"
fi

echo "=============================================================================="
echo " [pfm-core-ledger-java] Verificando Infraestrutura Base Local..."
echo "=============================================================================="
for pair in "PostgreSQL:5432" "Redpanda_Kafka:9092" "Redis_Valkey:6379" "LocalStack:4566"; do
  name="${pair%%:*}"
  port="${pair##*:}"
  if ! (echo > "/dev/tcp/127.0.0.1/$port") >/dev/null 2>&1; then
    echo " [ERRO] $name nao esta acessivel em localhost:$port!"
    echo "        Execute primeiro: /home/dantas/workspace/pfm-platform/init-local-development.sh"
    exit 1
  fi
  echo " [OK] $name ativo em localhost:$port"
done

echo "=============================================================================="
echo " [pfm-core-ledger-java] Iniciando em Foreground (gRPC :9090 | HTTP :8082)"
echo " Logs ao vivo no terminal e gravados em: $(pwd)/logs/app-local.log"
echo "=============================================================================="
exec "$MVN_CMD" spring-boot:run -Dspring-boot.run.profiles=local 2>&1 | tee -a ./logs/app-local.log
