package ru.marketplace.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.marketplace.model.FulfillmentStatus;
import ru.marketplace.model.PaymentStatus;

import java.time.Instant;
import java.util.List;

@Schema(description = "Заказ в админке мерчанта: покупатель, ПВЗ, позиции и статусы оплаты/исполнения")
public record MerchantOrderDTO(
        @Schema(description = "Номер заказа (6 символов) — его видит покупатель") String publicId,
        Instant createdAt,
        @Schema(description = "Когда Т-Банк подтвердил оплату; null — ещё не оплачен", nullable = true) Instant paidAt,
        PaymentStatus paymentStatus,
        FulfillmentStatus fulfillmentStatus,
        @Schema(description = "Трек-номер СДЭК, который ввёл мерчант", nullable = true) String trackingNumber,
        String customerName,
        String customerPhone,
        String customerEmail,
        @Schema(description = "Код ПВЗ СДЭК, куда отправлять посылку") String pvzCode,
        @Schema(nullable = true) String pvzCity,
        @Schema(nullable = true) String pvzAddress,
        @Schema(description = "Комментарий покупателя к заказу", nullable = true) String comment,
        @Schema(description = "Сумма товаров в копейках (доставка оплачивается при получении и сюда не входит)")
        long itemsTotalKopecks,
        List<MerchantOrderItemDTO> items
) {
}
