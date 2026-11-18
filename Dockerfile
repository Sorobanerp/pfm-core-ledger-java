# ============================================================================
# Stage 1: Build Stage (Java 25 LTS + Maven 3.9)
# ============================================================================
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /workspace

COPY pom.xml ./
COPY src/ src/
RUN mvn clean package -DskipTests -B && \
    java -Djarmode=tools -jar target/pfm-core-ledger-java-*.jar extract --layers --destination extracted

# ============================================================================
# Stage 2: Runtime Stage (Google Distroless Base + Java 25 LTS Minimal JRE)
# Zero shell, non-root user (UID 65532), Virtual Threads & ZGC Generational
# ============================================================================
FROM gcr.io/distroless/base-debian12:nonroot
WORKDIR /application

COPY --from=builder /opt/java/openjdk /opt/java/openjdk
COPY --from=builder /workspace/extracted/dependencies/ ./
COPY --from=builder /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder /workspace/extracted/application/ ./

ENV PATH="/opt/java/openjdk/bin:${PATH}" \
    JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=75.0 -Dspring.threads.virtual.enabled=true"

EXPOSE 8080 9090
USER nonroot:nonroot

ENTRYPOINT ["/opt/java/openjdk/bin/java", "org.springframework.boot.loader.launch.JarLauncher"]
