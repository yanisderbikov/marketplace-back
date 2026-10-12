package ru.marketplace.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/** Новый порядок фотографий товара: все его фото, первое — главное. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Порядок фотографий товара")
public class PhotoOrderRequestDTO {

    @NotNull(message = "не передан порядок фото")
    @Schema(description = "id всех фото товара в нужном порядке")
    private List<UUID> photoIds;
}
