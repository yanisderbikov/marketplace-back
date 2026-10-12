package ru.marketplace.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/** Новый порядок каталога: все товары магазина, первый — первый на витрине. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Порядок товаров в каталоге")
public class ProductOrderRequestDTO {

    @NotNull(message = "не передан порядок товаров")
    @Schema(description = "id всех товаров магазина в нужном порядке")
    private List<UUID> productIds;
}
