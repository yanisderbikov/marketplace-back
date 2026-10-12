package ru.marketplace.model;

/**
 * Через кого магазин принимает оплату: свой терминал Т-Банка или свой магазин в ЮKassa.
 * Деньги в обоих случаях идут мерчанту напрямую — платформа только создаёт платёж его ключами.
 */
public enum PaymentProvider {
    TINKOFF,
    YOOKASSA
}
