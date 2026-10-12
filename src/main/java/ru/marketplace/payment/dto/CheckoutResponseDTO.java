package ru.marketplace.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Заказ создан — покупателя нужно отправить на страницу оплаты Т-Банка")
public record CheckoutResponseDTO(
        @Schema(description = "Публичный номер заказа", example = "A1B2C3") String orderPublicId,
        @Schema(description = "Ссылка на оплату в Т-Банке") String paymentUrl
) {
}
