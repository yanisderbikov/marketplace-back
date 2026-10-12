package ru.marketplace.email;

/**
 * Почта в логах без локальной части: «s***@example.com» — по логу видно домен, но не адрес (152-ФЗ, минимизация).
 * Одна функция на весь бэк: и вход по коду, и отправка писем пишут в лог только маску.
 */
public final class EmailMasking {

    private EmailMasking() {
    }

    public static String mask(String email) {
        if (email == null) {
            return "null";
        }
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return trimmed.charAt(0) + "***" + trimmed.substring(at);
    }
}
