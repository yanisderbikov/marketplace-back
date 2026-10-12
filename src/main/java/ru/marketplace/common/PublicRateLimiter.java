package ru.marketplace.common;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Лимиты публичных ручек витрины с одного IP ({@link ClientIp}); сверх лимита — 429 {error}.
 * Каждая из них тратит чужие ресурсы на каждый запрос, и без лимита одним скриптом можно было бы:
 * <ul>
 *   <li>checkout — плодить заказы и платежи Init на терминале мерчанта (и строки в БД);</li>
 *   <li>delivery-cost — жечь квоту API СДЭК платформы;</li>
 *   <li>статус заказа — держать потоки на синхронных GetState и забивать пул запросов.</li>
 * </ul>
 * Значения — константы, а не env: это защита от злоупотребления, подобранная под живого покупателя
 * с запасом (общий IP мобильного оператора или офиса), а не настройка, которую нужно крутить на проде.
 */
@Component
public class PublicRateLimiter {

    /** Живой покупатель оформляет единицы заказов в час; 30 — с запасом на общий IP */
    static final int CHECKOUTS_PER_HOUR = 30;
    /** Пересчёт на каждую смену ПВЗ или корзины */
    static final int DELIVERY_COSTS_PER_HOUR = 120;
    /** Страница успеха опрашивает статус раз в пару секунд до 90 секунд — около 45 запросов на заказ */
    static final int ORDER_STATUSES_PER_HOUR = 600;

    static final String CHECKOUT_LIMIT = "Слишком много заказов с вашего адреса. Попробуйте позже";
    static final String DELIVERY_COST_LIMIT = "Слишком много расчётов доставки. Попробуйте позже";
    static final String ORDER_STATUS_LIMIT = "Слишком много запросов статуса заказа. Попробуйте позже";

    private static final Duration WINDOW = Duration.ofHours(1);

    private final FixedWindowRateLimiter checkout;
    private final FixedWindowRateLimiter deliveryCost;
    private final FixedWindowRateLimiter orderStatus;

    public PublicRateLimiter(Clock clock) {
        this.checkout = new FixedWindowRateLimiter(CHECKOUTS_PER_HOUR, WINDOW, CHECKOUT_LIMIT, clock);
        this.deliveryCost = new FixedWindowRateLimiter(DELIVERY_COSTS_PER_HOUR, WINDOW, DELIVERY_COST_LIMIT, clock);
        this.orderStatus = new FixedWindowRateLimiter(ORDER_STATUSES_PER_HOUR, WINDOW, ORDER_STATUS_LIMIT, clock);
    }

    public void checkout(String ip) {
        checkout.acquire(ip);
    }

    public void deliveryCost(String ip) {
        deliveryCost.acquire(ip);
    }

    public void orderStatus(String ip) {
        orderStatus.acquire(ip);
    }
}
