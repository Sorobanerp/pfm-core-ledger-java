package br.com.pfm.ledger.application.port.out;

import java.util.UUID;

/**
 * Outbound Port for Envelope Encryption (ADR-006) backed by AWS KMS / Azure Key Vault / GCP Cloud KMS.
 */
public interface EnvelopeEncryptionKmsPort {

    byte[] encryptForTenant(UUID tenantId, byte[] plaintext);

    byte[] decryptForTenant(UUID tenantId, byte[] ciphertext);
}
