package ru.marketplace.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/** Позиция корзины: id товара (+ вариант) и количество. Цена берётся с сервера. */
@Data
@Schema(description = "Позиция корзины")
public class CheckoutItemDTO {

    @NotNull(message = "Не указан товар")
    @Schema(description = "ID товара магазина", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID productId;

    /** Обязателен, если у товара есть варианты; цена берётся из варианта. */
    @Schema(description = "ID варианта товара (размер, цвет…); обязателен, если у товара есть варианты", nullable = true)
    private UUID variantId;

    @NotNull(message = "Не указано количество")
    @Min(value = 1, message = "Количество — от 1 до 99")
    @Max(value = 99, message = "Количество — от 1 до 99")
    @Schema(description = "Количество, 1..99", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer quantity;
}
