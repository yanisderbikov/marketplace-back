package ru.marketplace.cdek.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/** Позиция корзины для расчёта доставки: id товара магазина + количество. Вес и габариты берутся с сервера. */
@Data
@Schema(description = "Позиция корзины для расчёта доставки")
public class DeliveryCostItemDTO {

    @NotNull(message = "Не указан товар")
    @Schema(description = "ID товара этого магазина")
    private UUID productId;

    /** На упаковку не влияет (вес и габариты задаются у товара), принимаем, чтобы фронт слал позиции корзины как есть */
    @Schema(description = "ID варианта товара (на расчёт не влияет)")
    private UUID variantId;

    /** Верхняя граница как на чекауте: каждая единица — отдельное место посылки, без лимита запрос к СДЭК раздувается */
    @NotNull(message = "Не указано количество")
    @Min(value = 1, message = "Количество — от 1 до 99")
    @Max(value = 99, message = "Количество — от 1 до 99")
    @Schema(description = "Количество, 1..99")
    private Integer quantity;
}
