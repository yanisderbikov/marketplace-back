package ru.marketplace.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Товар глазами владельца магазина: все поля, включая выключенные из продажи товары. Цены в копейках. */
@Builder
@Schema(description = "Товар каталога мерчанта")
public record MerchantProductDTO(
        UUID id,
        String name,
        @Schema(description = "Может быть пустой строкой") String description,
        @Schema(description = "Первое фото — главное (обложка карточки)") List<MerchantPhotoDTO> photos,
        @Schema(example = "149000") long priceKopecks,
        @Schema(description = "Зачёркнутая «старая» цена; null или больше priceKopecks", nullable = true) Long crossedPriceKopecks,
        @Schema(description = "Показывается ли товар на витрине") boolean active,
        @Schema(description = "Продаётся по предзаказу") boolean preorder,
        @Schema(description = "Место в каталоге (с 0)") int orderNumber,
        @Schema(nullable = true) Integer weightGrams,
        @Schema(nullable = true) Integer lengthCm,
        @Schema(nullable = true) Integer widthCm,
        @Schema(nullable = true) Integer heightCm,
        @Schema(description = "Варианты в порядке показа на витрине") List<ProductVariantDTO> variants,
        Instant createdAt,
        Instant updatedAt
) {
}
