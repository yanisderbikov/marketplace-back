package ru.marketplace.order;

/**
 * Письма об оплаченном заказе: мерчанту — «новый заказ», покупателю — подтверждение оплаты.
 * Вызывает модуль оплаты ПОСЛЕ коммита перевода заказа в PAID (вебхук, поллер, страница успеха).
 * Реализация идемпотентна: при paidNotified=true ничего не шлёт; ошибки отправки логирует, не бросает.
 */
public interface OrderNotificationService {
    void onOrderPaid(Long orderId);
}
