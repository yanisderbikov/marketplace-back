package ru.marketplace.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Ключ фото, которое браузер уже загрузил в S3 по presigned URL. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Ключ загруженного фото товара")
public class PhotoRegisterRequestDTO {

    @NotBlank(message = "не указан ключ файла")
    @Schema(description = "key из ответа presign", example = "shops/0b6f…/products/5a1c…/9e2d….jpg")
    private String key;
}
