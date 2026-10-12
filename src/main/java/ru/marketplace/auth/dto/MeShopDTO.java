package ru.marketplace.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Магазин текущего мерчанта — минимум для меню админки")
public record MeShopDTO(
        UUID id,
        String slug,
        String name,
        @Schema(description = "false — магазин выключен супер-админом, витрина отвечает 404") boolean active
) {
}
