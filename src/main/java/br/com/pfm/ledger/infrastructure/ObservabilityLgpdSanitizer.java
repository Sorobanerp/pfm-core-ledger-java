package br.com.pfm.ledger.infrastructure;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * OpenTelemetry W3C Trace Context propagation, Datadog Unified Service Tagging,
 * and LGPD PII sanitizer (CPF, Card PAN, PDF passwords) for structured JSON logs.
 */
public final class ObservabilityLgpdSanitizer {

    public static final String DEFAULT_OTLP_ENDPOINT =
            "https://otel-collector.pfm.internal:4318/v1/traces";
    public static final String SERVICE_NAME = "pfm-core-ledger-java";

    private static final Pattern CPF_PATTERN =
            Pattern.compile("\\b\\d{3}\\.?\\d{3}\\.?\\d{3}-\\d{2}\\b|\\b\\d{11}\\b");
    private static final Pattern PAN_PATTERN =
            Pattern.compile("\\b(?:\\d[ -]*?){13,16}\\b");
    private static final Pattern PASSWORD_KV_PATTERN =
            Pattern.compile("(?i)(\"?(?:pdf_?password|password)\"?\\s*[:=]\\s*\"?)([^\"\\s,}]+)(\"?)");
    private static final Pattern W3C_TRACEPARENT_PATTERN =
            Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private ObservabilityLgpdSanitizer() {
    }

    public static String resolveOtlpEndpoint(String configuredEndpoint) {
        if (configuredEndpoint == null || configuredEndpoint.isBlank() || "noop".equalsIgnoreCase(configuredEndpoint.trim())) {
            return "noop";
        }
        String trimmed = configuredEndpoint.trim();
        if (trimmed.endsWith("/v1/traces")) {
            return trimmed;
        }
        return trimmed.replaceAll("/+$", "") + "/v1/traces";
    }

    public static String ensureTraceparent(String incoming) {
        if (incoming != null && W3C_TRACEPARENT_PATTERN.matcher(incoming.trim()).matches()) {
            return incoming.trim();
        }
        byte[] traceBytes = new byte[16];
        byte[] spanBytes = new byte[8];
        SECURE_RANDOM.nextBytes(traceBytes);
        SECURE_RANDOM.nextBytes(spanBytes);
        HexFormat hex = HexFormat.of();
        return "00-" + hex.formatHex(traceBytes) + "-" + hex.formatHex(spanBytes) + "-01";
    }

    public static String maskSensitivePii(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String masked = PASSWORD_KV_PATTERN.matcher(raw).replaceAll("$1[REDACTED]$3");
        masked = PAN_PATTERN.matcher(masked).replaceAll("[PAN_MASKED]");
        masked = CPF_PATTERN.matcher(masked).replaceAll("[CPF_MASKED]");
        return masked;
    }

    public static String formatStructuredJsonLog(String level, String message, String traceparent) {
        String validTraceparent = ensureTraceparent(traceparent);
        String[] parts = validTraceparent.split("-");
        String traceId = parts[1];
        String spanId = parts[2];
        String safeLevel = (level == null || level.isBlank()) ? "INFO" : level.trim().toUpperCase();
        String sanitizedMessage = maskSensitivePii(message)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");

        return String.format(
                "{\"level\":\"%s\",\"dd.service\":\"%s\",\"dd.env\":\"production\",\"dd.version\":\"0.1.0\","
                        + "\"dd.trace_id\":\"%s\",\"dd.span_id\":\"%s\",\"traceparent\":\"%s\",\"message\":\"%s\"}",
                safeLevel, SERVICE_NAME, traceId, spanId, validTraceparent, sanitizedMessage);
    }
}
