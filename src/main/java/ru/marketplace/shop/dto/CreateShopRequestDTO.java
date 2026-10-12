package ru.marketplace.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Онбординг: магазин создаётся с минимумом полей, остальное мерчант заполняет в «Дизайне» и «Настройках». */
@Data
@Schema(description = "Создание магазина на онбординге")
public class CreateShopRequestDTO {
    @Schema(description = "Название магазина, 2–120 символов", example = "Свечная лавка")
    private String name;
    @Schema(description = "Адрес витрины /shop/{slug}: 3–32 символа, латиница, цифры и дефис; после создания не меняется",
            example = "candle-shop")
    private String slug;
    @Schema(description = "Цвет бренда #RRGGBB — из него строится вся тема витрины", example = "#7f95a7")
    private String brandColor;
}
