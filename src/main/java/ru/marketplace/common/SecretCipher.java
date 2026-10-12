package ru.marketplace.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Шифрование секретов мерчантов в БД (пароль терминала Т-Банка): AES-256-GCM.
 * Дамп БД без PAYMENT_SECRETS_KEY не даёт принимать платежи от имени магазинов.
 * Формат: base64(iv(12 байт) | шифртекст с тегом GCM). IV случайный на каждое шифрование —
 * один и тот же пароль у двух магазинов даёт разный шифртекст.
 */
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${payment.secrets.key}") String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PAYMENT_SECRETS_KEY должен быть base64 от 32 байт (openssl rand -base64 32)", e);
        }
        if (raw.length != KEY_BYTES) {
            // Падаем на старте: с коротким ключом пароли терминалов либо не зашифруются, либо не расшифруются
            throw new IllegalStateException("PAYMENT_SECRETS_KEY должен быть base64 ровно от 32 байт, сейчас " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** null → null. */
    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось зашифровать секрет", e);
        }
    }

    /** null → null. Подменённый шифртекст или чужой ключ — IllegalStateException (GCM проверяет целостность). */
    public String decrypt(String enc) {
        if (enc == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(enc.trim());
            if (all.length <= IV_BYTES) {
                throw new IllegalStateException("Шифртекст секрета слишком короткий");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Не удалось расшифровать секрет", e);
        }
    }
}
