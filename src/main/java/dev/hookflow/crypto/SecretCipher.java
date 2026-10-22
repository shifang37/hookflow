package dev.hookflow.crypto;

import dev.hookflow.config.HookFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Encrypts the per-endpoint signing secret at rest.
 *
 * <p>Format: {@code [12-byte IV][ciphertext][16-byte GCM tag]}.
 * The data key is derived from a master passphrase supplied through the
 * {@code HOOKFLOW_MASTER_KEY} environment variable. Losing that passphrase makes
 * every stored endpoint secret unreadable, which is why the operations guide
 * calls it out explicitly.
 *
 * <p>This is a deliberate v0.1 simplification: a production deployment would use
 * a KMS with per-tenant data keys and envelope encryption. The schema already
 * carries {@code secret_key_version} so rotating the master key is a migration,
 * not a redesign.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MIN_PASSPHRASE_LENGTH = 32;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    private final short keyVersion;

    /**
     * Primary constructor used by the container. Explicitly annotated because the
     * secondary constructor below exists purely for tests, and Spring refuses to
     * guess when a class has more than one candidate.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SecretCipher(HookFlowProperties properties) {
        this(passphraseOf(properties), properties.security() == null ? 1 : properties.security().keyVersion());
    }

    /** Visible for tests and for callers that do not have the whole properties tree. */
    public SecretCipher(String passphrase, short keyVersion) {
        if (passphrase == null || passphrase.length() < MIN_PASSPHRASE_LENGTH) {
            throw new IllegalStateException(
                    "hookflow.security.master-key must be at least " + MIN_PASSPHRASE_LENGTH
                            + " characters. Set the HOOKFLOW_MASTER_KEY environment variable.");
        }
        this.key = new SecretKeySpec(sha256(passphrase), "AES");
        this.keyVersion = keyVersion;
        if (log.isDebugEnabled()) {
            log.debug("SecretCipher initialised with key version {}", keyVersion);
        }
    }

    private static String passphraseOf(HookFlowProperties properties) {
        return properties.security() == null ? null : properties.security().masterKey();
    }

    public short keyVersion() {
        return keyVersion;
    }

    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encrypt secret", e);
        }
    }

    public byte[] decrypt(byte[] payload) {
        try {
            byte[] iv = Arrays.copyOfRange(payload, 0, IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(payload, IV_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to decrypt secret (wrong master key?)", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
