package ru.marketplace.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.marketplace.model.PaymentStatus;

import java.util.List;

/**
 * Статус заказа для страницы после оплаты. Только состав и суммы: ФИО, телефон и почта
 * наружу не выходят — номер заказа виден в адресной строке и его могут подсмотреть.
 */
@Schema(description = "Статус оплаты заказа по публичному номеру (без персональных данных)")
public record OrderStatusDTO(
        @Schema(description = "Публичный номер заказа", example = "A1B2C3") String publicId,
        @Schema(description = "Адрес витрины магазина заказа") String shopSlug,
        @Schema(description = "Название магазина") String shopName,
        @Schema(description = "Статус оплаты", example = "PAID") PaymentStatus paymentStatus,
        @Schema(description = "Сумма товаров в копейках (доставка оплачивается при получении)") long itemsTotalKopecks,
        @Schema(description = "Позиции заказа") List<OrderStatusItemDTO> items
) {
}
