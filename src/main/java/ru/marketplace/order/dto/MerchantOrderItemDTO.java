package ru.marketplace.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Позиция заказа — снапшот на момент оформления")
public record MerchantOrderItemDTO(
        @Schema(description = "null — товар уже удалён из каталога (название и цена сохранились в заказе)", nullable = true)
        UUID productId,
        @Schema(description = "Название товара на момент заказа") String productName,
        int quantity,
        @Schema(description = "Цена за штуку в копейках") long unitPriceKopecks
) {
}
