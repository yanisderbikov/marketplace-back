package ru.marketplace.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Можно ли занять адрес витрины")
public record SlugAvailabilityDTO(
        boolean available,
        @Schema(description = "Почему нельзя — готовый текст для подсказки под полем; null, если можно", nullable = true)
        String reason
) {

    public static SlugAvailabilityDTO ok() {
        return new SlugAvailabilityDTO(true, null);
    }

    public static SlugAvailabilityDTO rejected(String reason) {
        return new SlugAvailabilityDTO(false, reason);
    }
}
