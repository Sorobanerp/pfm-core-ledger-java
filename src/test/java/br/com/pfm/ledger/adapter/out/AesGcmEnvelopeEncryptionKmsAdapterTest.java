package br.com.pfm.ledger.adapter.out;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AesGcmEnvelopeEncryptionKmsAdapter Pure Unit Tests")
class AesGcmEnvelopeEncryptionKmsAdapterTest {

    private final AesGcmEnvelopeEncryptionKmsAdapter adapter = new AesGcmEnvelopeEncryptionKmsAdapter();

    @Test
    void shouldEncryptAndDecryptSuccessfullyForTenant() {
        UUID tenantId = UUID.randomUUID();
        String originalText = "Extrato Itaú: Saldo R$ 10.450,22 - Criptografia em repouso";
        byte[] plaintext = originalText.getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = adapter.encryptForTenant(tenantId, plaintext);
        assertNotNull(encrypted);
        assertTrue(encrypted.length > plaintext.length);

        byte[] decrypted = adapter.decryptForTenant(tenantId, encrypted);
        assertNotNull(decrypted);
        assertEquals(originalText, new String(decrypted, StandardCharsets.UTF_8));
    }

    @Test
    void shouldFailDecryptionWhenTenantMismatch() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        byte[] plaintext = "Dados ultrasecretos".getBytes(StandardCharsets.UTF_8);

        byte[] encryptedA = adapter.encryptForTenant(tenantA, plaintext);

        assertThrows(IllegalStateException.class, () -> adapter.decryptForTenant(tenantB, encryptedA));
    }

    @Test
    void shouldFailDecryptionWhenCiphertextIsTampered() {
        UUID tenantId = UUID.randomUUID();
        byte[] plaintext = "Dados autenticados".getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = adapter.encryptForTenant(tenantId, plaintext);
        // Tamper with one byte in the payload
        encrypted[encrypted.length - 1] ^= 0x5A;

        assertThrows(IllegalStateException.class, () -> adapter.decryptForTenant(tenantId, encrypted));
    }

    @Test
    void shouldThrowOnNullArguments() {
        UUID tenantId = UUID.randomUUID();
        byte[] data = new byte[]{1, 2, 3};

        assertThrows(NullPointerException.class, () -> adapter.encryptForTenant(null, data));
        assertThrows(NullPointerException.class, () -> adapter.encryptForTenant(tenantId, null));
        assertThrows(NullPointerException.class, () -> adapter.decryptForTenant(null, data));
        assertThrows(NullPointerException.class, () -> adapter.decryptForTenant(tenantId, null));
    }
}
