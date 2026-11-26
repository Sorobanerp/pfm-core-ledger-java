package br.com.pfm.ledger.application.port.out;

import java.util.UUID;

/**
 * Outbound Port for LGPD-compliant PII encryption (AES-256-GCM) and deterministic
 * HMAC-SHA256 blind indexing for lookups without exposing plaintext in PostgreSQL.
 */
public interface SensitiveDataCipherPort {

    String encryptPii(UUID tenantId, String plaintext);

    String decryptPii(UUID tenantId, String ciphertextBase64);

    String blindIndexHmacSha256(String rawSensitiveValue);
}
