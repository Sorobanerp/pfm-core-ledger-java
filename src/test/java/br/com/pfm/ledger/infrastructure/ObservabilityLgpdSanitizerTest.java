package br.com.pfm.ledger.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ObservabilityLgpdSanitizer Unit Tests")
class ObservabilityLgpdSanitizerTest {

    @Test
    void shouldResolveOtlpEndpointWithNoopFallbackAndSuffix() {
        assertEquals("noop", ObservabilityLgpdSanitizer.resolveOtlpEndpoint(null));
        assertEquals("noop", ObservabilityLgpdSanitizer.resolveOtlpEndpoint("   "));
        assertEquals("noop", ObservabilityLgpdSanitizer.resolveOtlpEndpoint("noop"));
        assertEquals(
                "http://datadog-agent.monitoring.svc.cluster.local:4318/v1/traces",
                ObservabilityLgpdSanitizer.resolveOtlpEndpoint("http://datadog-agent.monitoring.svc.cluster.local:4318"));
        assertEquals(
                "http://datadog-agent.monitoring.svc.cluster.local:4318/v1/traces",
                ObservabilityLgpdSanitizer.resolveOtlpEndpoint("http://datadog-agent.monitoring.svc.cluster.local:4318/v1/traces"));
    }

    @Test
    void shouldPropagateOrGenerateW3cTraceparent() {
        String existing = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        assertEquals(existing, ObservabilityLgpdSanitizer.ensureTraceparent(existing));

        String generated = ObservabilityLgpdSanitizer.ensureTraceparent("invalid");
        assertTrue(generated.startsWith("00-"));
        assertTrue(generated.endsWith("-01"));
        assertEquals(55, generated.length());

        String generatedFromNull = ObservabilityLgpdSanitizer.ensureTraceparent(null);
        assertEquals(55, generatedFromNull.length());
    }

    @Test
    void shouldMaskCpfCardPanAndPdfPasswordsInStructuredJsonLogs() {
        assertEquals("", ObservabilityLgpdSanitizer.maskSensitivePii(null));
        assertEquals("", ObservabilityLgpdSanitizer.maskSensitivePii(""));

        String raw = "Cliente CPF 123.456.789-01 cartao 4532 1122 3344 5566 pdfPassword=segredo123";
        String masked = ObservabilityLgpdSanitizer.maskSensitivePii(raw);
        assertFalse(masked.contains("123.456.789-01"));
        assertFalse(masked.contains("4532 1122 3344 5566"));
        assertFalse(masked.contains("segredo123"));
        assertTrue(masked.contains("[CPF_MASKED]"));
        assertTrue(masked.contains("[PAN_MASKED]"));
        assertTrue(masked.contains("[REDACTED]"));

        String json = ObservabilityLgpdSanitizer.formatStructuredJsonLog(
                null, "Evento CPF 11122233344 \"password\":\"abc\"", null);
        assertTrue(json.contains("\"dd.service\":\"pfm-core-ledger-java\""));
        assertTrue(json.contains("\"level\":\"INFO\""));
        assertTrue(json.contains("[CPF_MASKED]"));
        assertTrue(json.contains("[REDACTED]"));

        String jsonWarn = ObservabilityLgpdSanitizer.formatStructuredJsonLog(
                "warn", "ok", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertTrue(jsonWarn.contains("\"level\":\"WARN\""));
        assertTrue(jsonWarn.contains("\"dd.trace_id\":\"4bf92f3577b34da6a3ce929d0e0e4736\""));
    }
}
