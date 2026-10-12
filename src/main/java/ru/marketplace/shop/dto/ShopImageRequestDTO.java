package ru.marketplace.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Ключ файла, загруженного в S3 по presigned URL")
public class ShopImageRequestDTO {
    @NotBlank(message = "не указан ключ файла")
    @Schema(description = "key из ответа presign", example = "shops/0b6f…/branding/logo/5a1c….png")
    private String key;
}
