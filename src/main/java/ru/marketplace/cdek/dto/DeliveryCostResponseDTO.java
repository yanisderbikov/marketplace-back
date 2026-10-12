package ru.marketplace.cdek.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Стоимость и срок доставки. Стоимость в рублях (как отдаёт СДЭК), а не в копейках:
 * на чекауте она только информирует — доставка оплачивается при получении и в платёж не входит.
 */
@Schema(description = "Стоимость и срок доставки СДЭК")
public record DeliveryCostResponseDTO(
        @Schema(description = "Стоимость доставки, руб.") BigDecimal cost,
        @Schema(description = "Минимальный срок доставки, дней") Integer periodMin,
        @Schema(description = "Максимальный срок доставки, дней") Integer periodMax,
        @Schema(description = "Код тарифа СДЭК") int tariffCode) {
}
