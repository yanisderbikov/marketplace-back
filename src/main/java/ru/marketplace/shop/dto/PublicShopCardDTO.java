package ru.marketplace.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "Карточка магазина на главной платформы")
public record PublicShopCardDTO(
        String slug,
        String name,
        String brandColor,
        @Schema(nullable = true) String logoUrl,
        @Schema(nullable = true) String heroImageUrl,
        @Schema(nullable = true) String tagline,
        @Schema(description = "Сколько товаров сейчас продаётся") long productsCount
) {
}
