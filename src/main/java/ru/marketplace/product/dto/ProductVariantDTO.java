package ru.marketplace.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** Вариант товара (размер/объём) со своей ценой: «80 мл» — 199000, «20 см» — 249000 (в копейках). */
@Schema(description = "Вариант товара")
public record ProductVariantDTO(
        UUID id,
        @Schema(example = "80 мл") String label,
        @Schema(description = "Цена варианта в копейках", example = "199000") long priceKopecks
) {
}
