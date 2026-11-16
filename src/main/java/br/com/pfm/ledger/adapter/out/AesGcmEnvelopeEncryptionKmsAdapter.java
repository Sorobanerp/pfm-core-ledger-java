package br.com.pfm.ledger.adapter.out;

import br.com.pfm.ledger.application.port.out.EnvelopeEncryptionKmsPort;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Outbound Adapter implementing AES-256-GCM Envelope Encryption with tenant-isolated DEKs (ADR-006).
 */
@Component
public class AesGcmEnvelopeEncryptionKmsAdapter implements EnvelopeEncryptionKmsPort {

    private static final String AES_GCM_NO_PADDING = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public byte[] encryptForTenant(UUID tenantId, byte[] plaintext) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        try {
            SecretKeySpec dek = deriveTenantDek(tenantId);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
            cipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(tenantId.toString().getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext);

            return ByteBuffer.allocate(IV_LENGTH_BYTES + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array();
        } catch (Exception ex) {
            throw new IllegalStateException("Envelope encryption failed for tenant " + tenantId, ex);
        }
    }

    @Override
    public byte[] decryptForTenant(UUID tenantId, byte[] ciphertext) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(ciphertext, "ciphertext must not be null");
        try {
            SecretKeySpec dek = deriveTenantDek(tenantId);
            ByteBuffer buffer = ByteBuffer.wrap(ciphertext);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] encryptedPayload = new byte[buffer.remaining()];
            buffer.get(encryptedPayload);

            Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
            cipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(tenantId.toString().getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(encryptedPayload);
        } catch (Exception ex) {
            throw new IllegalStateException("Envelope decryption failed for tenant " + tenantId, ex);
        }
    }

    private SecretKeySpec deriveTenantDek(UUID tenantId) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = digest.digest(("alias/pfm-tenant-master-key:" + tenantId).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, "AES");
    }
}
