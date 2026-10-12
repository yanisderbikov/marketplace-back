package ru.marketplace.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

/** Товар на витрине: только то, что видит покупатель. Цены в копейках, фронт форматирует сам. */
@Builder
@Schema(description = "Товар витрины")
public record PublicProductDTO(
        UUID id,
        String name,
        String description,
        @Schema(description = "Ссылки на фото (presigned GET), первое — главное") List<String> photos,
        @Schema(example = "149000") long priceKopecks,
        @Schema(nullable = true) Long crossedPriceKopecks,
        @Schema(description = "Скидка относительно зачёркнутой цены, %, округление вниз", nullable = true) Integer discountPercent,
        boolean preorder,
        @Schema(description = "Если не пусто — покупатель обязан выбрать вариант") List<ProductVariantDTO> variants
) {
}
