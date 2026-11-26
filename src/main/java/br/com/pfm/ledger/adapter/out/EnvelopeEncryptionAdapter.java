package br.com.pfm.ledger.adapter.out;

import br.com.pfm.ledger.application.port.out.EnvelopeEncryptionKmsPort;
import br.com.pfm.ledger.application.port.out.SensitiveDataCipherPort;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Outbound Adapter combining tenant-isolated AES-256-GCM Envelope Encryption with
 * HMAC-SHA256 blind indexing for LGPD Brazil compliance.
 */
@Component
public class EnvelopeEncryptionAdapter implements SensitiveDataCipherPort {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final EnvelopeEncryptionKmsPort kmsPort;
    private final byte[] hmacPepperBytes;

    public EnvelopeEncryptionAdapter(
            EnvelopeEncryptionKmsPort kmsPort,
            @Value("${pfm.security.blind-index-pepper:pfm-lgpd-blind-index-dev-pepper}") String blindIndexPepper
    ) {
        this.kmsPort = Objects.requireNonNull(kmsPort, "kmsPort must not be null");
        String resolved = (blindIndexPepper == null || blindIndexPepper.isBlank())
                ? "pfm-lgpd-blind-index-dev-pepper"
                : blindIndexPepper;
        this.hmacPepperBytes = resolved.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String encryptPii(UUID tenantId, String plaintext) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        byte[] encrypted = kmsPort.encryptForTenant(tenantId, plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    @Override
    public String decryptPii(UUID tenantId, String ciphertextBase64) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(ciphertextBase64, "ciphertextBase64 must not be null");
        byte[] decoded = Base64.getDecoder().decode(ciphertextBase64);
        byte[] decrypted = kmsPort.decryptForTenant(tenantId, decoded);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    @Override
    public String blindIndexHmacSha256(String rawSensitiveValue) {
        Objects.requireNonNull(rawSensitiveValue, "rawSensitiveValue must not be null");
        String canonical = rawSensitiveValue.trim().toLowerCase();
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(hmacPepperBytes, HMAC_SHA256));
            byte[] digest = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 blind index computation failed", ex);
        }
    }
}
