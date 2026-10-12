package ru.marketplace.common;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretCipherTest {

    private static String key(int bytes, int seed) {
        byte[] raw = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            raw[i] = (byte) (i * 7 + seed);
        }
        return Base64.getEncoder().encodeToString(raw);
    }

    private final SecretCipher cipher = new SecretCipher(key(32, 1));

    @Test
    void roundTrip() {
        String enc = cipher.encrypt("TinkoffPass_123 пароль");
        assertFalse(enc.contains("TinkoffPass"));
        assertEquals("TinkoffPass_123 пароль", cipher.decrypt(enc));
    }

    @Test
    void randomIvGivesDifferentCiphertextForSamePlain() {
        // Один пароль у двух магазинов не должен выдавать себя одинаковым шифртекстом
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"));
    }

    @Test
    void formatIsIvPlusCiphertextWithTag() {
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("abc"));
        // 12 байт IV + 3 байта данных + 16 байт тега GCM
        assertEquals(12 + 3 + 16, raw.length);
    }

    @Test
    void nullStaysNull() {
        assertNull(cipher.encrypt(null));
        assertNull(cipher.decrypt(null));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("secret"));
        raw[raw.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void otherKeyCannotDecrypt() {
        SecretCipher other = new SecretCipher(key(32, 2));
        String enc = cipher.encrypt("secret");
        assertThrows(IllegalStateException.class, () -> other.decrypt(enc));
    }

    @Test
    void garbageIsRejected() {
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("не base64"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(Base64.getEncoder().encodeToString(new byte[5])));
    }

    @Test
    void keyMustBeExactly32Bytes() {
        assertThrows(IllegalStateException.class, () -> new SecretCipher(key(16, 1)));
        assertThrows(IllegalStateException.class, () -> new SecretCipher(key(33, 1)));
        assertThrows(IllegalStateException.class, () -> new SecretCipher("%%%не-base64%%%"));
        assertThrows(IllegalStateException.class, () -> new SecretCipher(""));
        assertThrows(IllegalStateException.class, () -> new SecretCipher(null));
    }
}
