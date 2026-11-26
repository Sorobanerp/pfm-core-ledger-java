package br.com.pfm.ledger.adapter.out;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EnvelopeEncryptionAdapter LGPD Pure Unit Tests")
class EnvelopeEncryptionAdapterTest {

    private final AesGcmEnvelopeEncryptionKmsAdapter kmsAdapter = new AesGcmEnvelopeEncryptionKmsAdapter();
    private final EnvelopeEncryptionAdapter adapter = new EnvelopeEncryptionAdapter(kmsAdapter, "test-pepper-32-bytes");

    @Test
    void shouldEncryptAndDecryptPiiWithinSameTenant() {
        UUID tenantId = UUID.randomUUID();
        String cpf = "123.456.789-01";

        String encrypted = adapter.encryptPii(tenantId, cpf);
        assertNotEquals(cpf, encrypted);
        assertEquals(cpf, adapter.decryptPii(tenantId, encrypted));
    }

    @Test
    void shouldRejectDecryptionAcrossDifferentTenants() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        String encrypted = adapter.encryptPii(tenantA, "123.456.789-01");
        assertThrows(IllegalStateException.class, () -> adapter.decryptPii(tenantB, encrypted));
    }

    @Test
    void shouldProduceDeterministicHmacSha256BlindIndex() {
        String idx1 = adapter.blindIndexHmacSha256(" 12345678901 ");
        String idx2 = adapter.blindIndexHmacSha256("12345678901");
        String idxOther = adapter.blindIndexHmacSha256("99999999999");

        assertEquals(64, idx1.length());
        assertEquals(idx1, idx2);
        assertNotEquals(idx1, idxOther);
    }
}
