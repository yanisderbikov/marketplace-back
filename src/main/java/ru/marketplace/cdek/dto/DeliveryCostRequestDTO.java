package ru.marketplace.cdek.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Запрос расчёта стоимости доставки СДЭК от ПВЗ магазина до ПВЗ покупателя")
public class DeliveryCostRequestDTO {

    @NotBlank(message = "Выберите пункт выдачи")
    @Schema(description = "Код ПВЗ СДЭК получателя (из /api/public/cdek/pvz)")
    private String pvzCode;

    @NotEmpty(message = "Корзина пуста")
    @Size(max = 100, message = "Слишком много позиций в корзине")
    @Valid
    @Schema(description = "Позиции корзины")
    private List<@NotNull(message = "Пустая позиция корзины") DeliveryCostItemDTO> items;
}
