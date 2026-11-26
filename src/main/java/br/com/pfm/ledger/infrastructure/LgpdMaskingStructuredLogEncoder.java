package br.com.pfm.ledger.infrastructure;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.logging.logback.StructuredLogEncoder;

/**
 * Custom Logback JSON encoder extending Spring Boot's {@link StructuredLogEncoder}
 * to automatically mask LGPD PII and sensitive fields (password, token, cpf,
 * card_number, secret) before writing to stdout.
 */
public class LgpdMaskingStructuredLogEncoder extends StructuredLogEncoder {

    @Override
    public byte[] encode(ILoggingEvent event) {
        byte[] rawBytes = super.encode(event);
        if (rawBytes == null || rawBytes.length == 0) {
            return rawBytes;
        }
        String rawJson = new String(rawBytes, StandardCharsets.UTF_8);
        String sanitized = ObservabilityLgpdSanitizer.maskSensitivePii(rawJson);
        return sanitized.getBytes(StandardCharsets.UTF_8);
    }
}
