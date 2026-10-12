package ru.marketplace.shop;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Правила адреса витрины /shop/{slug}. Адрес выбирается один раз на онбординге и потом не меняется:
 * на него ведут ссылки покупателей, поэтому правила строгие — только то, что одинаково читается
 * в URL, в письме и вслух.
 */
public final class SlugPolicy {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 32;

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,30})[a-z0-9]$");
    private static final Pattern ALLOWED_CHARS = Pattern.compile("^[a-z0-9-]+$");

    /**
     * Адреса, которые нельзя занять: пути фронта и бэка, служебные слова платформы.
     * slug-available — ещё и литеральный путь /api/public/shops/slug-available: магазин с таким адресом
     * был бы недоступен через GET /api/public/shops/{slug}.
     */
    public static final Set<String> RESERVED = Set.of(
            "admin", "api", "auth", "login", "logout", "shop", "shops", "cart", "checkout", "success",
            "offer", "privacy", "static", "assets", "public", "www", "help", "support", "marketplace",
            "settings", "design", "orders", "catalog", "analytics", "onboarding",
            "slug-available");

    private SlugPolicy() {
    }

    /** trim + нижний регистр: «MyShop » и «myshop» — один и тот же адрес. null → пустая строка. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Почему этот (уже нормализованный) адрес занять нельзя; null — по правилам можно.
     * Занятость другим магазином здесь не проверяется — это запрос в БД.
     */
    public static String problem(String slug) {
        if (slug == null || slug.isEmpty()) {
            return "Укажите адрес витрины";
        }
        if (slug.length() < MIN_LENGTH || slug.length() > MAX_LENGTH) {
            return "Адрес витрины: от " + MIN_LENGTH + " до " + MAX_LENGTH + " символов";
        }
        if (!ALLOWED_CHARS.matcher(slug).matches()) {
            return "Адрес витрины: только латинские буквы, цифры и дефис";
        }
        if (slug.startsWith("-") || slug.endsWith("-")) {
            return "Адрес витрины должен начинаться и заканчиваться буквой или цифрой";
        }
        if (slug.contains("--")) {
            return "Адрес витрины: два дефиса подряд нельзя";
        }
        if (!PATTERN.matcher(slug).matches()) {
            // Сюда не должны попадать: проверки выше покрывают шаблон, но шаблон — источник правды
            return "Некорректный адрес витрины";
        }
        if (RESERVED.contains(slug)) {
            return "Этот адрес зарезервирован, выберите другой";
        }
        return null;
    }
}
