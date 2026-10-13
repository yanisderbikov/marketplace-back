package ru.marketplace.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Свой домен витрины: магазин открывается на нём с корня, без /shop/{slug}. */
@Data
@Schema(description = "Свой домен витрины")
public class ShopDomainRequestDTO {
    @Schema(description = "Домен, например shop.example.ru или магазин.рф (схема, путь и www отбрасываются); "
            + "пусто — отвязать домен", nullable = true, example = "shop.example.ru")
    private String domain;
}
