package ru.marketplace.payment;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Подпись запросов к Т-Банку и проверка подписи его нотификаций (перенос anyforms TinkoffTokenService).
 * Token = SHA-256 (hex) от склеенных значений корневых скалярных параметров + Password,
 * отсортированных по имени ключа. Вложенные объекты (Receipt, DATA) в подпись не входят.
 * Пароль не из env, как в anyforms, а аргументом: у каждого магазина свой терминал.
 */
@Service
public class TinkoffTokenService {

    private static final String TOKEN = "Token";
    private static final String PASSWORD = "Password";

    public String sign(Map<String, String> rootParams, String password) {
        Objects.requireNonNull(password, "Пароль терминала не задан");
        TreeMap<String, String> params = new TreeMap<>(rootParams);
        params.put(PASSWORD, password);
        String concatenated = String.join("", params.values());
        return sha256Hex(concatenated);
    }

    /**
     * Проверка Token нотификации паролем терминала магазина. Нотификация — сырой JSON, разобранный
     * в Map: в подпись входят все корневые скалярные поля кроме Token, включая те, о которых
     * мы не знаем (банк добавляет поля без предупреждения).
     */
    public boolean verify(Map<String, Object> notification, String password) {
        if (notification == null || password == null) {
            return false;
        }
        Object token = notification.get(TOKEN);
        if (!(token instanceof String received) || received.isBlank()) {
            return false;
        }
        String expected = sign(scalarRootParams(notification), password);
        // Сравнение за постоянное время: по времени ответа не подобрать подпись побайтно
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                received.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Корневые скалярные поля нотификации в том виде, в каком их подписывает банк:
     * строки как есть, числа без экспоненты, булевы — "true"/"false". null, объекты и массивы пропускаем.
     */
    static Map<String, String> scalarRootParams(Map<String, Object> notification) {
        TreeMap<String, String> params = new TreeMap<>();
        notification.forEach((key, value) -> {
            if (TOKEN.equals(key) || value == null) {
                return;
            }
            if (value instanceof String s) {
                params.put(key, s);
            } else if (value instanceof Boolean b) {
                params.put(key, b.toString());
            } else if (value instanceof BigDecimal d) {
                params.put(key, d.toPlainString());
            } else if (value instanceof Number n) {
                params.put(key, n.toString());
            }
            // Map (Receipt, DATA) и List в подпись не входят
        });
        return params;
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
