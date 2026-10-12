package ru.marketplace.cdek;

/**
 * Калькулятор СДЭК не смог посчитать доставку (нет токена, ошибка API, пустой/битый ответ).
 * Контроллер отдаёт её как 502 {"error": "..."}: проблема на стороне внешнего сервиса, а не в запросе покупателя.
 */
public class CdekCalculationException extends RuntimeException {

    public CdekCalculationException(String message) {
        super(message);
    }
}
