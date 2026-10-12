package ru.marketplace.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Фото товара в админке. id нужен, чтобы удалить фото или поменять порядок;
 * url — presigned GET (бакет приватный), живёт несколько часов.
 */
@Schema(description = "Фото товара")
public record MerchantPhotoDTO(
        UUID id,
        @Schema(description = "Ссылка на чтение (presigned GET)") String url
) {
}
