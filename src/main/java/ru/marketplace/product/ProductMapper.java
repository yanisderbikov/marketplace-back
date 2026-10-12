package ru.marketplace.product;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.marketplace.model.Product;
import ru.marketplace.model.ProductPhoto;
import ru.marketplace.model.ProductVariant;
import ru.marketplace.product.dto.MerchantPhotoDTO;
import ru.marketplace.product.dto.MerchantProductDTO;
import ru.marketplace.product.dto.ProductVariantDTO;
import ru.marketplace.product.dto.PublicProductDTO;
import ru.marketplace.storage.S3FileStorage;

import java.util.Comparator;
import java.util.List;

/**
 * Product → DTO (перенос ConverterProductsImpl из anyforms). Фото лежат строками product_photo с ключами S3,
 * а не листингом папки бакета: бакет приватный, поэтому URL — presigned GET, его подписывает S3FileStorage
 * (и держит стабильным несколько часов, чтобы браузер кэшировал картинки).
 * Вызывать внутри транзакции: фото и варианты — LAZY, open-in-view выключен.
 */
@Component
@RequiredArgsConstructor
public class ProductMapper {

    /**
     * Порядок берём из position/orderNumber, а не из порядка списка: после перестановки в той же транзакции
     * список в памяти остаётся в старом порядке (@OrderBy работает только при загрузке).
     */
    private static final Comparator<ProductPhoto> PHOTO_ORDER =
            Comparator.comparing(ProductPhoto::getPosition, Comparator.nullsLast(Comparator.naturalOrder()));
    private static final Comparator<ProductVariant> VARIANT_ORDER =
            Comparator.comparing(ProductVariant::getOrderNumber, Comparator.nullsLast(Comparator.naturalOrder()));

    private final S3FileStorage s3FileStorage;

    public MerchantProductDTO toMerchant(Product product) {
        return MerchantProductDTO.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .photos(sortedPhotos(product).stream()
                        .map(photo -> new MerchantPhotoDTO(photo.getId(), s3FileStorage.publicUrl(photo.getS3Key())))
                        .toList())
                .priceKopecks(product.getPriceKopecks())
                .crossedPriceKopecks(product.getCrossedPriceKopecks())
                .active(Boolean.TRUE.equals(product.getActive()))
                .preorder(Boolean.TRUE.equals(product.getPreorder()))
                .orderNumber(product.getOrderNumber() == null ? 0 : product.getOrderNumber())
                .weightGrams(product.getWeightGrams())
                .lengthCm(product.getLengthCm())
                .widthCm(product.getWidthCm())
                .heightCm(product.getHeightCm())
                .variants(variants(product))
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }

    public PublicProductDTO toPublic(Product product) {
        return PublicProductDTO.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .photos(sortedPhotos(product).stream()
                        .map(photo -> s3FileStorage.publicUrl(photo.getS3Key()))
                        .toList())
                .priceKopecks(product.getPriceKopecks())
                .crossedPriceKopecks(product.getCrossedPriceKopecks())
                .discountPercent(discountPercent(product.getPriceKopecks(), product.getCrossedPriceKopecks()))
                .preorder(Boolean.TRUE.equals(product.getPreorder()))
                .variants(variants(product))
                .build();
    }

    /**
     * Скидка для плашки «−N%» на витрине. В anyforms процент хранился отдельным полем и мог разойтись с ценами;
     * здесь он всегда считается из цен: floor((зачёркнутая − цена) × 100 / зачёркнутая).
     * Без зачёркнутой цены или если она не больше цены — null (плашки нет).
     */
    public static Integer discountPercent(Long priceKopecks, Long crossedPriceKopecks) {
        if (priceKopecks == null || crossedPriceKopecks == null || crossedPriceKopecks <= priceKopecks) {
            return null;
        }
        // Обе цены положительные и ограничены валидацией — произведение в long не переполняется,
        // а целочисленное деление положительных чисел и есть округление вниз
        return (int) ((crossedPriceKopecks - priceKopecks) * 100 / crossedPriceKopecks);
    }

    private static List<ProductPhoto> sortedPhotos(Product product) {
        if (product.getPhotos() == null) {
            return List.of();
        }
        return product.getPhotos().stream().sorted(PHOTO_ORDER).toList();
    }

    private static List<ProductVariantDTO> variants(Product product) {
        if (product.getVariants() == null) {
            return List.of();
        }
        return product.getVariants().stream()
                .sorted(VARIANT_ORDER)
                .map(v -> new ProductVariantDTO(v.getId(), v.getLabel(), v.getPriceKopecks()))
                .toList();
    }
}
