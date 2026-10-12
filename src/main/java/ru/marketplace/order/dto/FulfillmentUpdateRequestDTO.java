package ru.marketplace.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Мерчант ведёт исполнение оплаченного заказа вручную: новый → отправлен (с трек-номером) → выполнен.
 * Статус строкой, а не enum: так на опечатку отвечаем понятным «Некорректный статус исполнения», а не общим 400.
 */
@Data
@Schema(description = "Смена статуса исполнения и трек-номера")
public class FulfillmentUpdateRequestDTO {
    @Schema(description = "Статус исполнения", allowableValues = {"NEW", "SHIPPED", "DONE", "CANCELLED"})
    private String fulfillmentStatus;
    @Schema(description = "Трек-номер СДЭК, до 64 символов; пусто — убрать", nullable = true)
    private String trackingNumber;
}
