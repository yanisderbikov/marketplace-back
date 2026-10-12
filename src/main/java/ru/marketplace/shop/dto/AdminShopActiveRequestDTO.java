package ru.marketplace.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Включить или выключить магазин")
public class AdminShopActiveRequestDTO {
    @NotNull(message = "Укажите, включён ли магазин")
    @Schema(description = "true — витрина работает, false — витрина 404 и заказы не принимаются",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "false")
    private Boolean active;
}
