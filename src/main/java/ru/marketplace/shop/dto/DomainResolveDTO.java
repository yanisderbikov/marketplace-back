package ru.marketplace.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Чья витрина живёт на домене: фронт открывает её с корня, гейтвей по 200 выпускает сертификат. */
@Schema(description = "Магазин на своём домене")
public record DomainResolveDTO(
        @Schema(example = "candle-shop") String slug,
        @Schema(description = "Домен магазина, как он сохранён", example = "shop.example.ru") String domain
) {
}
