package ru.marketplace.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Позиция заказа")
public record OrderStatusItemDTO(
        @Schema(description = "Название товара на момент заказа") String productName,
        @Schema(description = "Количество", example = "2") int quantity,
        @Schema(description = "Цена за штуку в копейках", example = "89000") long unitPriceKopecks
) {
}
