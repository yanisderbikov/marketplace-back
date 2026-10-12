package ru.marketplace.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.marketplace.util.MoneyUtil;

import java.util.List;

/**
 * Создание и обновление товара — полная форма карточки (PUT заменяет все поля, а не только переданные,
 * как было в anyforms): так фронту не нужно помнить, какие поля «не менять».
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Карточка товара: создание и обновление")
public class ProductSaveRequestDTO {

    public static final int NAME_MAX = 255;
    /** Описание в TEXT, лимит — защита витрины от мегабайтных простыней в каждом ответе каталога */
    public static final int DESCRIPTION_MAX = 10_000;
    /** Т-Банк не принимает платёж меньше 1 ₽ */
    public static final long MIN_PRICE_KOPECKS = MoneyUtil.MIN_PAYABLE_KOPECKS;
    /** 100 млн ₽: защита от мусора и переполнения сумм заказа (цена × количество) */
    public static final long MAX_PRICE_KOPECKS = 10_000_000_000L;
    public static final int DIMENSION_MAX = 100_000;
    public static final int VARIANTS_MAX = 50;

    @NotBlank(message = "укажите название товара")
    @Size(max = NAME_MAX, message = "название не длиннее 255 символов")
    private String name;

    /** Обязательно, но может быть пустой строкой */
    @NotNull(message = "описание обязательно (может быть пустым)")
    @Size(max = DESCRIPTION_MAX, message = "описание не длиннее 10000 символов")
    private String description;

    @NotNull(message = "укажите цену")
    @Min(value = MIN_PRICE_KOPECKS, message = "цена не меньше 1 ₽")
    @Max(value = MAX_PRICE_KOPECKS, message = "цена слишком большая")
    @Schema(description = "Цена в копейках", example = "149000")
    private Long priceKopecks;

    /** null — без зачёркнутой цены; иначе строго больше priceKopecks (проверяет сервис) */
    @Max(value = MAX_PRICE_KOPECKS, message = "зачёркнутая цена слишком большая")
    @Schema(description = "Зачёркнутая цена в копейках, больше priceKopecks", nullable = true, example = "199000")
    private Long crossedPriceKopecks;

    @NotNull(message = "укажите, продаётся ли товар")
    @Schema(description = "Показывать товар на витрине")
    private Boolean active;

    @NotNull(message = "укажите, предзаказ ли это")
    @Schema(description = "Товар продаётся по предзаказу (плашка и пояснение на витрине)")
    private Boolean preorder;

    /** Вес и габариты для расчёта доставки СДЭК; null — берутся значения упаковки по умолчанию */
    @Min(value = 1, message = "вес от 1 до 100000 г")
    @Max(value = DIMENSION_MAX, message = "вес от 1 до 100000 г")
    @Schema(nullable = true)
    private Integer weightGrams;

    @Min(value = 1, message = "длина от 1 до 100000 см")
    @Max(value = DIMENSION_MAX, message = "длина от 1 до 100000 см")
    @Schema(nullable = true)
    private Integer lengthCm;

    @Min(value = 1, message = "ширина от 1 до 100000 см")
    @Max(value = DIMENSION_MAX, message = "ширина от 1 до 100000 см")
    @Schema(nullable = true)
    private Integer widthCm;

    @Min(value = 1, message = "высота от 1 до 100000 см")
    @Max(value = DIMENSION_MAX, message = "высота от 1 до 100000 см")
    @Schema(nullable = true)
    private Integer heightCm;

    /**
     * Варианты товара (размер/объём — цена). Набор заменяется целиком, порядок в списке — порядок на витрине:
     * вариант с id обновляется, без id — создаётся, не перечисленные удаляются. null — то же, что пустой список.
     */
    @Valid
    @Size(max = VARIANTS_MAX, message = "не больше 50 вариантов")
    private List<ProductVariantRequestDTO> variants;
}
