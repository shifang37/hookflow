package dev.hookflow.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static final String PASSPHRASE = "unit-test-passphrase-at-least-32-chars";
    private static final byte[] PLAINTEXT = "super-secret-signing-key".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTripsPlaintext() {
        SecretCipher cipher = new SecretCipher(PASSPHRASE, (short) 1);
        byte[] encrypted = cipher.encrypt(PLAINTEXT);
        assertThat(encrypted).isNotEqualTo(PLAINTEXT);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(PLAINTEXT);
    }

    @Test
    void usesAFreshIvPerEncryption() {
        SecretCipher cipher = new SecretCipher(PASSPHRASE, (short) 1);
        byte[] first = cipher.encrypt(PLAINTEXT);
        byte[] second = cipher.encrypt(PLAINTEXT);
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(PLAINTEXT);
        assertThat(cipher.decrypt(second)).isEqualTo(PLAINTEXT);
    }

    @Test
    void failsOnTamperedCiphertext() {
        SecretCipher cipher = new SecretCipher(PASSPHRASE, (short) 1);
        byte[] encrypted = cipher.encrypt(PLAINTEXT);
        encrypted[encrypted.length - 1] ^= 0x01;
        assertThatThrownBy(() -> cipher.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to decrypt");
    }

    @Test
    void failsWithTheWrongMasterKey() {
        byte[] encrypted = new SecretCipher(PASSPHRASE, (short) 1).encrypt(PLAINTEXT);
        SecretCipher other = new SecretCipher("a-completely-different-passphrase-32chars", (short) 1);
        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsWeakPassphraseAtStartup() {
        assertThatThrownBy(() -> new SecretCipher("too-short", (short) 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HOOKFLOW_MASTER_KEY");
    }

    @Test
    void exposesKeyVersion() {
        assertThat(new SecretCipher(PASSPHRASE, (short) 3).keyVersion()).isEqualTo((short) 3);
    }
}
