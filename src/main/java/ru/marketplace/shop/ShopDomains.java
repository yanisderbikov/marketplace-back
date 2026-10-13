package ru.marketplace.shop;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Правила своего домена витрины. Мерчант вставляет домен как угодно («https://Магазин.рф/», «www.shop.ru»),
 * храним один канонический вид: punycode в нижнем регистре, без схемы, пути, порта, точки на конце и www.
 * Домен платформы (хост FRONT_URL) и его поддомены занять нельзя — на них живёт сама платформа.
 */
@Component
public class ShopDomains {

    static final int MAX_LENGTH = 253;
    private static final String WWW = "www.";
    private static final Pattern SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*://", Pattern.CASE_INSENSITIVE);
    private static final Pattern LABEL = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");

    private final String platformHost;

    public ShopDomains(@Value("${front.url}") String frontUrl) {
        String host = frontUrl == null ? null : URI.create(frontUrl.trim()).getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("FRONT_URL без хоста: не понять, какой домен принадлежит платформе");
        }
        this.platformHost = host.toLowerCase(Locale.ROOT);
    }

    /**
     * Канонический вид домена из ввода мерчанта; null — пустой ввод. Невалидный домен —
     * IllegalArgumentException с текстом для мерчанта.
     */
    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = SCHEME.matcher(raw.trim()).replaceFirst("");
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        if (value.contains(":") || value.contains("@")) {
            throw new IllegalArgumentException("Домен — без порта и логина, например shop.example.ru");
        }
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        String ascii;
        try {
            ascii = IDN.toASCII(value, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректный домен");
        }
        if (ascii.startsWith(WWW)) {
            ascii = ascii.substring(WWW.length());
        }
        String problem = problem(ascii);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        return ascii;
    }

    /**
     * Хост из запроса (Host витрины, SNI у гейтвея) → домен, под которым магазин мог его сохранить.
     * Без валидации: несуществующий домен просто не найдётся.
     */
    public static String lookupKey(String host) {
        if (host == null) {
            return null;
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.startsWith(WWW)) {
            value = value.substring(WWW.length());
        }
        return value.isEmpty() || value.length() > MAX_LENGTH ? null : value;
    }

    private String problem(String domain) {
        if (domain.isEmpty() || domain.length() > MAX_LENGTH) {
            return "Некорректный домен";
        }
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            return "Укажите домен целиком, например shop.example.ru";
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                return "Некорректный домен: латиница, цифры, дефис и точки между частями";
            }
        }
        if (DIGITS.matcher(labels[labels.length - 1]).matches()) {
            return "Нужен домен, а не IP-адрес";
        }
        if (domain.equals(platformHost) || domain.endsWith("." + platformHost)) {
            return "Это домен платформы — укажите свой";
        }
        return null;
    }
}
