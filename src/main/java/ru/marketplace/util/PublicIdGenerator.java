package ru.marketplace.util;

import java.security.SecureRandom;
import java.util.function.Predicate;

/**
 * Публичный номер заказа (6 символов A-Z0-9): его видит покупатель и он же OrderId в Т-Банке.
 * Внутренний BIGSERIAL наружу не отдаём — по нему видно число заказов и легко перебрать чужие.
 */
public final class PublicIdGenerator {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    public static final int LENGTH = 6;
    private static final int MAX_ATTEMPTS = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PublicIdGenerator() {
    }

    /** Случайный номер без проверки уникальности. */
    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** Номер, которого ещё нет ({@code exists} — обычно orderRepository::existsByPublicId). */
    public static String generateUnique(Predicate<String> exists) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = generate();
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Не удалось сгенерировать уникальный публичный номер");
    }
}
