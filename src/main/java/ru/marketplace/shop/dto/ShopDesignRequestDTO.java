package ru.marketplace.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Вкладка «Дизайн»: всё, что видно на витрине, кроме картинок (они грузятся отдельными ручками). */
@Data
@Schema(description = "Оформление витрины. Пустые строки сохраняются как null")
public class ShopDesignRequestDTO {
    @Schema(description = "Название магазина, 2–120 символов")
    private String name;
    @Schema(description = "Цвет бренда #RRGGBB", example = "#7f95a7")
    private String brandColor;
    @Schema(description = "Заголовок витрины под шапкой, до 160 символов", nullable = true)
    private String tagline;
    @Schema(description = "Короткое описание рядом с фото витрины, до 600 символов", nullable = true)
    private String description;
    @Schema(description = "Блок «О магазине» под каталогом, до 4000 символов", nullable = true)
    private String about;
}
