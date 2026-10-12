package ru.marketplace.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Магазин в таблице супер-админа")
public record AdminShopDTO(
        UUID id,
        String slug,
        String name,
        @Schema(description = "false — выключен: витрина 404, оформление заказа 409") boolean active,
        @Schema(description = "Почта владельца; null — у магазина нет владельца", nullable = true) String ownerEmail,
        Instant createdAt,
        @Schema(description = "Все товары каталога, включая выключенные") long productsCount,
        @Schema(description = "Оплаченные заказы за всё время") long paidOrdersCount,
        @Schema(description = "Сумма оплаченных заказов за всё время, в копейках") long paidTotalKopecks,
        @Schema(description = "Включён и всё настроено (оплата, доставка, продавец)") boolean acceptsOrders
) {
}
