package ru.marketplace.payment;

import ru.marketplace.payment.dto.CheckoutRequestDTO;
import ru.marketplace.payment.dto.CheckoutResponseDTO;
import ru.marketplace.payment.dto.OrderStatusDTO;

public interface CheckoutService {

    /**
     * Оформить заказ на витрине магазина: проверить корзину по серверным ценам, сразу создать заказ
     * (AWAITING_PAYMENT) и платёж в Т-Банке терминалом магазина.
     * 404 — нет магазина, 409 — магазин не принимает заказы, 400 — ошибки корзины/данных,
     * 502 — Т-Банк не создал платёж (заказ остаётся FAILED).
     */
    CheckoutResponseDTO checkout(String slug, CheckoutRequestDTO request);

    /**
     * Статус оплаты заказа по публичному номеру для страницы успеха; если заказ ещё ждёт оплаты,
     * а банк давно не спрашивали, — синхронно спросить GetState. 404 — нет такого заказа.
     */
    OrderStatusDTO orderStatus(String publicId);
}
