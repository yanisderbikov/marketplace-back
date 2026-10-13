package ru.marketplace.common;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Enumeration;

/**
 * IP клиента для лимитов запросов — одна функция на все ручки (вход, чекаут, доставка, статус заказа).
 * <p>
 * За прокси (гейтвей marketplace-gateway, nginx) remoteAddr — адрес самого прокси, а клиент — в X-Forwarded-For.
 * Гейтвей перезаписывает X-Forwarded-For одним адресом клиента (сам разбирает цепочку Railway-прокси).
 * Берём ПОСЛЕДНИЙ элемент: его дописывает доверенный пограничный прокси (адрес, с которого к нему пришли),
 * а всё левее клиент может прислать сам. Раньше брали первый элемент — и лимит обходился подстановкой
 * случайного X-Forwarded-For в каждый запрос. Расчёт на ровно один доверенный прокси перед приложением;
 * если их станет больше, последний элемент будет адресом внутреннего прокси — лимит станет общим, но не дырявым.
 * Без заголовка (прямое подключение, локальный запуск) — remoteAddr.
 */
public final class ClientIp {

    static final String FORWARDED_FOR = "X-Forwarded-For";
    /** Адрес IPv6 укладывается в 45 символов; длиннее — мусор из заголовка, обрезаем ключ кэша лимитов */
    static final int MAX_IP_LENGTH = 64;

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String last = lastForwarded(request);
        String ip = last != null ? last : request.getRemoteAddr();
        if (ip == null) {
            return null;
        }
        return ip.length() > MAX_IP_LENGTH ? ip.substring(0, MAX_IP_LENGTH) : ip;
    }

    /**
     * Последний элемент последнего заголовка X-Forwarded-For: прокси может дописать адрес отдельной
     * строкой заголовка, а не через запятую, — тогда его значение тоже последнее.
     */
    private static String lastForwarded(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(FORWARDED_FOR);
        if (headers == null) {
            return null;
        }
        String lastHeader = null;
        while (headers.hasMoreElements()) {
            String value = headers.nextElement();
            if (value != null && !value.isBlank()) {
                lastHeader = value;
            }
        }
        if (lastHeader == null) {
            return null;
        }
        String[] parts = lastHeader.split(",");
        String last = parts.length == 0 ? "" : parts[parts.length - 1].trim();
        return last.isEmpty() ? null : last;
    }
}
