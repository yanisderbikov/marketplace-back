package ru.marketplace.model;

/** Статус исполнения заказа — ведёт мерчант вручную в админке. */
public enum FulfillmentStatus {
    NEW,
    SHIPPED,
    DONE,
    CANCELLED
}
