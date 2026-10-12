package ru.marketplace.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Строка разбивки отчёта по товарам. */
@Schema(description = "Продажи одного товара за период")
public record ProductSalesDTO(
        @Schema(description = "Название товара на момент заказа") String productName,
        @Schema(description = "Куплено штук") long quantity,
        @Schema(description = "Сумма по товару в копейках") long totalKopecks
) {
}
