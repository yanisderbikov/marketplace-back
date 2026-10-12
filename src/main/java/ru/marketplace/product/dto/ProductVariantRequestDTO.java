package ru.marketplace.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Вариант товара в запросе сохранения. Порядок в списке — порядок показа на витрине.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Вариант товара в запросе сохранения")
public class ProductVariantRequestDTO {
    /**
     * ID существующего варианта — обновить его (сохраняет вариант в корзинах покупателей).
     * Без id создаётся новый вариант.
     */
    @Schema(nullable = true)
    private UUID id;

    /** Название варианта: «80 мл», «20 см». */
    @NotBlank(message = "укажите название варианта")
    @Size(max = ProductSaveRequestDTO.NAME_MAX, message = "название варианта не длиннее 255 символов")
    private String label;

    /** Цена варианта в копейках — её и платит покупатель, выбравший вариант. */
    @NotNull(message = "укажите цену варианта")
    @Min(value = ProductSaveRequestDTO.MIN_PRICE_KOPECKS, message = "цена варианта не меньше 1 ₽")
    @Max(value = ProductSaveRequestDTO.MAX_PRICE_KOPECKS, message = "цена варианта слишком большая")
    @Schema(example = "199000")
    private Long priceKopecks;
}
