package ru.marketplace.shop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SlugPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"abc", "candle-shop", "a1b", "shop2", "123", "a-b-c", "lunasvecha",
            "abcdefghijklmnopqrstuvwxyz012345"})
    void acceptsValidSlugs(String slug) {
        assertNull(SlugPolicy.problem(slug), slug);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ab", "a", "abcdefghijklmnopqrstuvwxyz0123456", "-abc", "abc-", "ab--c",
            "my_shop", "my shop", "магазин", "MyShop", "a.b.c", "abc/def"})
    void rejectsInvalidSlugs(String slug) {
        assertNotNull(SlugPolicy.problem(slug), slug);
    }

    @Test
    void lengthIsBetween3And32() {
        assertNull(SlugPolicy.problem("a".repeat(3)));
        assertNull(SlugPolicy.problem("a".repeat(32)));
        assertEquals("Адрес витрины: от 3 до 32 символов", SlugPolicy.problem("a".repeat(33)));
        assertEquals("Адрес витрины: от 3 до 32 символов", SlugPolicy.problem("ab"));
    }

    @Test
    void explainsWhatIsWrong() {
        assertEquals("Укажите адрес витрины", SlugPolicy.problem(""));
        assertEquals("Адрес витрины: только латинские буквы, цифры и дефис", SlugPolicy.problem("my_shop"));
        assertEquals("Адрес витрины должен начинаться и заканчиваться буквой или цифрой", SlugPolicy.problem("-shop"));
        assertEquals("Адрес витрины: два дефиса подряд нельзя", SlugPolicy.problem("my--shop"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "api", "auth", "login", "logout", "shop", "shops", "cart", "checkout", "success",
            "offer", "privacy", "static", "assets", "public", "www", "help", "support", "marketplace",
            "settings", "design", "orders", "catalog", "analytics", "onboarding", "slug-available"})
    void reservedSlugsAreRejected(String slug) {
        assertEquals("Этот адрес зарезервирован, выберите другой", SlugPolicy.problem(slug));
    }

    @Test
    void normalizeTrimsAndLowercases() {
        assertEquals("myshop", SlugPolicy.normalize("  MyShop "));
        assertEquals("", SlugPolicy.normalize(null));
        assertNull(SlugPolicy.problem(SlugPolicy.normalize(" Candle-Shop ")));
    }
}
