package ru.marketplace.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Текущий мерчант по данным БД (а не JWT)")
public record MeDTO(
        String email,
        String name,
        @Schema(description = "Почта в ADMIN_SUPER_EMAILS: фронт показывает группу меню «платформа»") boolean superAdmin,
        @Schema(description = "Магазин мерчанта; null — онбординг ещё не пройден", nullable = true) MeShopDTO shop
) {
}
